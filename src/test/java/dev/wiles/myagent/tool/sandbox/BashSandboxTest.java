package dev.wiles.myagent.tool.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.BashTool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalScope;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.CommandGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bash 和沙箱接在一起的那几条规矩（照 Claude Code）。这台机器上跑不了 bubblewrap，
 * 用一个假沙箱：包一层「先打印 [sandboxed] 再执行」，看命令到底是不是从沙箱里过的。真隔离见 docs/guide.md 的 Linux 冒烟。
 */
class BashSandboxTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();
    private ApprovalVerdict answer = ApprovalVerdict.yes();
    private ApprovalVerdict denyRule = null;

    /** 记账的审批：被问一次记一笔；deny 规则单独一条路。 */
    private final ApprovalPolicy approval = new ApprovalPolicy() {
        public ApprovalVerdict decide(ApprovalRequest request) {
            asked.add(request.subject() + " | " + request.reason());
            return answer;
        }

        @Override
        public ApprovalVerdict denyOnly(ApprovalRequest request) {
            return denyRule;
        }
    };

    private static final Sandbox FAKE = new Sandbox() {
        public String name() {
            return "fake";
        }

        public String unavailable() {
            return null;
        }

        public List<String> wrap(List<String> argv, Path ws, Path scratch, Endpoint proxy, SandboxSettings s) {
            List<String> a = new ArrayList<>(List.of("sh", "-c", "echo [sandboxed]; exec \"$@\"", "sh"));
            a.addAll(argv);
            return a;
        }
    };

    private static SandboxSettings on(boolean allowUnsandboxed) {
        return new SandboxSettings(true, false, allowUnsandboxed, true, List.of(), List.of(), List.of(), false);
    }

    private BashTool bash(SandboxSettings settings, Sandbox sandbox) {
        return new BashTool(new Workspace(tmp), new CommandGuard(), approval, new SandboxRunner(settings, sandbox, approval));
    }

    private ToolResult run(BashTool tool, String command, boolean disable) {
        return tool.execute(M.createObjectNode().put("command", command).put("dangerously_disable_sandbox", disable));
    }

    @AfterEach
    void clear() {
        ApprovalScope.clear();
    }

    @Test
    @DisplayName("沙箱开着：本来要问人的命令在沙箱里直接跑，不问（照 Claude Code 的 auto-allow）")
    void sandboxedCommandsAreAutoAllowed() {
        ToolResult r = run(bash(on(true), FAKE), "rm -f nothing.txt; echo done", false);
        assertTrue(r.ok(), r.content());
        assertTrue(r.content().contains("[sandboxed]"), "得从沙箱里过: " + r.content());
        assertTrue(r.content().contains("done"));
        assertTrue(asked.isEmpty(), "沙箱里跑的不该问人: " + asked);
    }

    @Test
    @DisplayName("deny 规则照样拦：自动放行不越过用户明说不许的")
    void denyRulesStillApply() {
        denyRule = ApprovalVerdict.no("被 policy.json 里的规则挡下: bash rm *");
        ToolResult r = run(bash(on(true), FAKE), "rm -f nothing.txt", false);
        assertFalse(r.ok());
        assertTrue(r.content().contains("policy.json"), r.content());
    }

    @Test
    @DisplayName("auto 下照样过审查（照 Claude Code：auto-allow 不跳过 auto 的审查）")
    void autoModeStillReviews() {
        ApprovalScope.set(null, false, request -> ApprovalVerdict.yes());
        run(bash(on(true), FAKE), "rm -f nothing.txt", false);
        assertEquals(1, asked.size(), "auto 下要走审批链（链里的审查器判）: " + asked);
    }

    @Test
    @DisplayName("命令守卫禁止的照样禁止，不进沙箱也不问")
    void forbiddenStaysForbidden() {
        ToolResult r = run(bash(on(true), FAKE), "sudo id", false);
        assertFalse(r.ok());
        assertFalse(r.content().contains("[sandboxed]"));
    }

    @Test
    @DisplayName("dangerously_disable_sandbox：在沙箱外跑要单独问，说清是沙箱外；批了才跑，不从沙箱过")
    void unsandboxedRetryNeedsApproval() {
        ToolResult r = run(bash(on(true), FAKE), "echo outside", true);
        assertTrue(r.ok(), r.content());
        assertFalse(r.content().contains("[sandboxed]"), r.content());
        assertEquals(1, asked.size(), "沙箱外跑哪怕是 echo 也要问: " + asked);
        assertTrue(asked.get(0).contains("沙箱外"), asked.get(0));

        asked.clear();
        answer = ApprovalVerdict.no("不行");
        assertFalse(run(bash(on(true), FAKE), "echo outside", true).ok());
    }

    @Test
    @DisplayName("沙箱外重跑不吃 allow 规则：用户存 git push origin main 时批的是推送，不是出沙箱（评测里真漏过一次）；deny 照样拦")
    void unsandboxedRetryIgnoresAllowRules() throws Exception {
        dev.wiles.myagent.tool.safety.PolicyStore store =
                new dev.wiles.myagent.tool.safety.PolicyStore(tmp.resolve("policy.json"));
        store.save(dev.wiles.myagent.tool.safety.ApprovalRules.of(
                List.of(dev.wiles.myagent.tool.safety.ApprovalRule.of(
                        dev.wiles.myagent.tool.safety.ApprovalKind.BASH, "git push origin main", "d")),
                List.of(dev.wiles.myagent.tool.safety.ApprovalRule.of(
                        dev.wiles.myagent.tool.safety.ApprovalKind.BASH, "git push --force *", "d"))));
        List<String> human = new ArrayList<>();
        ApprovalPolicy gate = new dev.wiles.myagent.tool.safety.PolicyGate(request -> {
            human.add(request.subject());
            return ApprovalVerdict.no("人没批");
        }, store);
        BashTool tool = new BashTool(new Workspace(tmp), new CommandGuard(), gate, new SandboxRunner(on(true), FAKE, gate));

        assertFalse(run(tool, "git push origin main", true).ok(), "规则罩着也得问人，人没批就不跑");
        assertEquals(List.of("git push origin main"), human);

        human.clear();
        ToolResult forced = run(tool, "git push --force origin main", true);
        assertFalse(forced.ok());
        assertTrue(human.isEmpty(), "deny 规则直接拦，不用问人");
    }

    @Test
    @DisplayName("沙箱里的 /tmp 跨命令留着（照 Claude Code 的 per-user temp）：评测里上一条下载到 /tmp 的，下一条就不见了")
    void sandboxTempPersistsAcrossCommands() {
        List<Path> scratches = new ArrayList<>();
        Sandbox recording = new Sandbox() {
            public String name() {
                return "rec";
            }

            public String unavailable() {
                return null;
            }

            public List<String> wrap(List<String> argv, Path ws, Path scratch, Endpoint proxy, SandboxSettings s) {
                scratches.add(scratch);
                return argv;
            }
        };
        BashTool tool = bash(on(true), recording);
        run(tool, "echo a", false);
        run(tool, "echo b", false);
        assertEquals(2, scratches.size());
        assertEquals(scratches.get(0), scratches.get(1), "两条命令该是同一个临时目录");
        assertTrue(java.nio.file.Files.isDirectory(scratches.get(0)), "跑完不删");
    }

    @Test
    @DisplayName("allowUnsandboxedCommands: false 时忽略 dangerously_disable_sandbox，照样进沙箱")
    void unsandboxedRetryCanBeDisabled() {
        ToolResult r = run(bash(on(false), FAKE), "echo x", true);
        assertTrue(r.content().contains("[sandboxed]"), r.content());
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("开了但平台不支持（原生 Windows）：不进沙箱，照常审批（照 Claude Code 的默认）")
    void unavailableFallsBackToNormalApproval() {
        Sandbox none = Sandbox.forPlatform("Windows 10", c -> true);
        ToolResult r = run(bash(on(true), none), "rm -f nothing.txt", false);
        assertTrue(r.ok(), r.content());
        assertEquals(1, asked.size(), "没有沙箱就照常问: " + asked);
    }

    @Test
    @DisplayName("工具说明里讲清沙箱：只能写工作区、出网走白名单、沙箱外跑要单独批")
    void descriptionMentionsTheSandbox() {
        String d = bash(on(true), FAKE).description();
        assertTrue(d.contains("沙箱"), d);
        assertTrue(d.contains("dangerously_disable_sandbox"), d);
        assertFalse(bash(SandboxSettings.OFF, FAKE).description().contains("dangerously_disable_sandbox"),
                "没开沙箱就别提这个参数");
    }
}
