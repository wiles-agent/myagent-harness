package dev.wiles.myagent.tool.safety;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跑代码的命令（python、node……）：命令文本看不出代码会做什么，所以先让 LLM 读代码判风险——
 * 低风险直接跑，高风险（或判不出来）照常问人。
 */
class CodeTriageTest {

    @TempDir
    Path ws;

    private final CommandGuard guard = new CommandGuard();

    /** 按剧本回话的模型，记下每次看到了什么。 */
    static final class FakeLlm implements LlmClient {
        final List<String> replies = new ArrayList<>();
        final List<List<ChatMessage>> seen = new ArrayList<>();
        boolean broken;

        FakeLlm says(String... r) {
            replies.addAll(List.of(r));
            return this;
        }

        @Override
        public String modelName() {
            return "fake";
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(List.copyOf(messages));
            if (broken) {
                throw new LlmException("网关挂了");
            }
            String text = replies.isEmpty() ? "HIGH\n没剧本" : replies.remove(0);
            return new ChatResponse("id", "fake",
                    List.of(new ChatResponse.Choice(0, ChatMessage.assistant(text), "stop")), null);
        }

        String lastPrompt() {
            List<ChatMessage> last = seen.get(seen.size() - 1);
            return last.get(last.size() - 1).content();
        }
    }

    @AfterEach
    void clear() {
        ApprovalScope.clear();
    }

    // ---------- 守卫：说清楚为什么要问 ----------

    @Test
    @DisplayName("python3 不是「未知命令」：要问是因为它跑代码，理由写清跑的是什么")
    void interpreterReasonIsHonest() {
        CommandRisk inline = guard.assess("python3 -c \"print(6*7)\"");
        assertTrue(inline.needsApproval());
        assertFalse(inline.reason().contains("未知命令"), inline.reason());
        assertTrue(inline.reason().contains("运行") && inline.reason().contains("python3"), inline.reason());

        assertTrue(guard.assess("python3 test_calc.py").reason().contains("test_calc.py"));
        assertTrue(guard.assess("/usr/bin/python3.11 -m pytest -q").reason().contains("pytest"));
        assertTrue(guard.assess("node scripts/build.js").reason().contains("scripts/build.js"));
    }

    @Test
    @DisplayName("认出命令里跑的代码：-c / -e 的内联代码、脚本路径、-m 模块；包装和安全的前缀段不碍事")
    void codeRunsParsed() {
        var inline = CommandGuard.codeRuns("python3 -c \"print(6*7)\"");
        assertEquals(1, inline.size());
        assertEquals("print(6*7)", inline.get(0).inlineCode());

        var script = CommandGuard.codeRuns("cd src && timeout 30 python3 -u test_calc.py --fast");
        assertEquals("test_calc.py", script.get(0).script());

        assertEquals("pytest", CommandGuard.codeRuns("python -m pytest -q").get(0).module());
        assertEquals("console.log(1)", CommandGuard.codeRuns("node -e 'console.log(1)'").get(0).inlineCode());
    }

    @Test
    @DisplayName("命令里还有别的要审批的段（curl、rm、pip install），或者根本没跑代码：不归代码审查管，照常问人")
    void onlyPureCodeRunsAreTriaged() {
        assertNull(CommandGuard.codeRuns("python3 a.py && curl http://x"));
        assertNull(CommandGuard.codeRuns("pip install requests"));
        assertNull(CommandGuard.codeRuns("ls -la"));
        assertNull(CommandGuard.codeRuns("python3 ~/evil.py"), "指向家目录的另有理由，不是代码审查能放的");
    }

    // ---------- LLM 判风险 ----------

    @Test
    @DisplayName("脚本内容交给模型看；LOW 就是低风险，理由带回来")
    void lowRiskWithScriptContent() throws Exception {
        Files.writeString(ws.resolve("test_calc.py"), "from calc import add\nassert add(2, 3) == 5\n");
        FakeLlm llm = new FakeLlm().says("LOW\n只导入本地模块做断言");
        CodeTriage.Result r = new CodeTriage(llm, ws).judge("python3 test_calc.py",
                CommandGuard.codeRuns("python3 test_calc.py"));
        assertTrue(r.low());
        assertEquals("只导入本地模块做断言", r.reason());
        assertTrue(llm.lastPrompt().contains("assert add(2, 3) == 5"), "模型要看到脚本内容");
    }

    @Test
    @DisplayName("HIGH、看不懂的回答、模型出错：都算高风险——判不出来就交给人")
    void failClosed() throws Exception {
        String cmd = "python3 -c \"import os; os.system('curl x')\"";
        var runs = CommandGuard.codeRuns(cmd);
        assertFalse(new CodeTriage(new FakeLlm().says("HIGH\n起子进程联网"), ws).judge(cmd, runs).low());
        assertFalse(new CodeTriage(new FakeLlm().says("嗯……"), ws).judge(cmd, runs).low());
        FakeLlm broken = new FakeLlm();
        broken.broken = true;
        CodeTriage.Result r = new CodeTriage(broken, ws).judge(cmd, runs);
        assertFalse(r.low());
        assertTrue(r.reason().contains("出错"), r.reason());
    }

    @Test
    @DisplayName("看不到代码就不问模型，直接高风险：脚本在工作区外、不存在，或者从 stdin 读")
    void unseenCodeIsHighWithoutAsking() {
        FakeLlm llm = new FakeLlm().says("LOW\n不该被问到");
        CodeTriage t = new CodeTriage(llm, ws);
        assertFalse(t.judge("python3 ../outside.py", CommandGuard.codeRuns("python3 ../outside.py")).low());
        assertFalse(t.judge("python3 missing.py", CommandGuard.codeRuns("python3 missing.py")).low());
        assertFalse(t.judge("python3", CommandGuard.codeRuns("python3")).low());
        assertTrue(llm.seen.isEmpty());
    }

    // ---------- 接进审批链 ----------

    private final List<ApprovalRequest> asked = new ArrayList<>();
    private final ApprovalPolicy human = request -> {
        asked.add(request);
        return ApprovalVerdict.no("人拒了");
    };

    @Test
    @DisplayName("低风险的代码直接放行，不问人；高风险照常问人，问的时候带上审查的理由")
    void gateRoutesByRisk() throws Exception {
        Files.writeString(ws.resolve("t.py"), "print(1)\n");
        CodeTriageGate low = new CodeTriageGate(human, new CodeTriage(new FakeLlm().says("LOW\n只打印"), ws));
        assertTrue(low.decide(ApprovalRequest.bash("python3 t.py", "要用 python3 运行代码")).approved());
        assertTrue(asked.isEmpty());

        CodeTriageGate high = new CodeTriageGate(human, new CodeTriage(new FakeLlm().says("HIGH\n会删工作区外的文件"), ws));
        assertFalse(high.decide(ApprovalRequest.bash("python3 t.py", "要用 python3 运行代码")).approved());
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).reason().contains("会删工作区外的文件"), asked.get(0).reason());
    }

    @Test
    @DisplayName("不归它管的照常问人：不是跑代码的命令、不是 bash、撞上 ask 规则（一定要人）")
    void gatePassesThroughOtherwise() throws Exception {
        Files.writeString(ws.resolve("t.py"), "print(1)\n");
        FakeLlm llm = new FakeLlm().says("LOW\nx", "LOW\nx", "LOW\nx");
        CodeTriageGate gate = new CodeTriageGate(human, new CodeTriage(llm, ws));
        gate.decide(ApprovalRequest.bash("pip install x", "装包"));
        gate.decide(ApprovalRequest.write("写入文件", "a.txt", "覆盖", ""));
        ApprovalScope.requiringHuman(() -> gate.decide(ApprovalRequest.bash("python3 t.py", "x")));
        assertEquals(3, asked.size());
        assertTrue(llm.seen.isEmpty(), "这几种都不该去问模型");
    }
}
