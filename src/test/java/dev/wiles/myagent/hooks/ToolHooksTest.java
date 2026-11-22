package dev.wiles.myagent.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 子 agent 的 PreToolUse hook：照 Claude Code 的格式和约定，跑真的 sh 命令。 */
class ToolHooksTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ToolHooks hooks(String json, Path cwd) throws Exception {
        return ToolHooks.parse(M.readTree(json), cwd);
    }

    @Test
    @DisplayName("matcher 认 Claude Code 的工具名（Bash 对 bash、Edit|Write 对 edit_file / write_file）；不写或 * 匹配全部")
    void matcherUnderstandsClaudeCodeNames(@TempDir Path cwd) throws Exception {
        ToolHooks h = hooks("""
                {"PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "exit 2"}]},
                  {"matcher": "Edit|Write", "hooks": [{"type": "command", "command": "exit 2"}]}
                ]}""", cwd);
        assertTrue(h.check("bash", "{}").blocked());
        assertTrue(h.check("edit_file", "{}").blocked());
        assertTrue(h.check("write_file", "{}").blocked());
        assertFalse(h.check("read_file", "{}").blocked(), "没对上的工具不跑 hook");

        ToolHooks all = hooks("{\"PreToolUse\": [{\"hooks\": [{\"type\": \"command\", \"command\": \"exit 2\"}]}]}", cwd);
        assertTrue(all.check("read_file", "{}").blocked());
        ToolHooks star = hooks("{\"PreToolUse\": [{\"matcher\": \"*\", \"hooks\": [{\"command\": \"exit 2\"}]}]}", cwd);
        assertTrue(star.check("grep", "{}").blocked());
    }

    @Test
    @DisplayName("退出码 2 拦下、stderr 是理由；stdin 收到 Claude Code 格式的 tool_name / tool_input；0 放行")
    void exitCodeTwoBlocksWithStderr(@TempDir Path cwd) throws Exception {
        ToolHooks h = hooks("""
                {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command",
                  "command": "input=$(cat); case \\"$input\\" in *DROP*) echo '只许查，不许改库' >&2; exit 2;; esac; exit 0"}]}]}
                """, cwd);
        ToolHooks.Decision blocked = h.check("bash", "{\"command\": \"psql -c 'DROP TABLE users'\"}");
        assertTrue(blocked.blocked());
        assertEquals("只许查，不许改库", blocked.reason());
        assertFalse(h.check("bash", "{\"command\": \"psql -c 'SELECT 1'\"}").blocked());

        ToolHooks echo = hooks("""
                {"PreToolUse": [{"hooks": [{"command": "cat > stdin.json; exit 0"}]}]}""", cwd);
        echo.check("bash", "{\"command\": \"ls\"}");
        JsonNode seen = M.readTree(cwd.resolve("stdin.json").toFile());
        assertEquals("PreToolUse", seen.path("hook_event_name").asText());
        assertEquals("Bash", seen.path("tool_name").asText(), "给 Claude Code 写的 hook 认得的名字");
        assertEquals("bash", seen.path("myagent_tool_name").asText());
        assertEquals("ls", seen.path("tool_input").path("command").asText());
    }

    @Test
    @DisplayName("stdout 的 JSON 也能拦：permissionDecision=deny 或 decision=block")
    void jsonDecisionBlocks(@TempDir Path cwd) throws Exception {
        ToolHooks deny = hooks("""
                {"PreToolUse": [{"hooks": [{"command": "echo '{\\"hookSpecificOutput\\": {\\"permissionDecision\\": \\"deny\\", \\"permissionDecisionReason\\": \\"不行\\"}}'"}]}]}
                """, cwd);
        assertTrue(deny.check("bash", "{}").blocked());
        assertEquals("不行", deny.check("bash", "{}").reason());
        ToolHooks block = hooks("""
                {"PreToolUse": [{"hooks": [{"command": "echo '{\\"decision\\": \\"block\\", \\"reason\\": \\"拦\\"}'"}]}]}
                """, cwd);
        assertEquals("拦", block.check("bash", "{}").reason());
    }

    @Test
    @DisplayName("hook 自己坏了（别的退出码、超时）不拦，记一条警告——坏掉的 hook 不该把 agent 卡住")
    void brokenHooksDoNotBlock(@TempDir Path cwd) throws Exception {
        ToolHooks broken = hooks("{\"PreToolUse\": [{\"hooks\": [{\"command\": \"exit 1\"}]}]}", cwd);
        ToolHooks.Decision d = broken.check("bash", "{}");
        assertFalse(d.blocked());
        assertFalse(d.warnings().isEmpty());
        assertTrue(ToolHooks.parse(M.readTree("{}"), cwd).isEmpty());
    }

    // ---------- 会话级：PostToolUse、additionalContext、settings.json ----------

    @Test
    @DisplayName("PostToolUse（照 Claude Code）：工具已经跑了拦不住；退出码 2 的 stderr、decision=block 的 reason、additionalContext 都交给模型")
    void postToolUseFeedsBack(@TempDir Path cwd) throws Exception {
        ToolHooks two = ToolHooks.parse(M.readTree("""
                {"PostToolUse": [{"matcher": "Write", "hooks": [{"command": "echo '这是生成的文件，改 schema.ts' >&2; exit 2"}]}]}"""),
                cwd, ToolHooks.POST);
        assertEquals("这是生成的文件，改 schema.ts", two.after("write_file", "{}", "已写入", true));
        assertNull(two.after("read_file", "{}", "x", true), "没对上的工具不跑");

        ToolHooks block = ToolHooks.parse(M.readTree("""
                {"PostToolUse": [{"hooks": [{"command": "echo '{\\"decision\\":\\"block\\",\\"reason\\":\\"lint 没过\\"}'"}]}]}"""),
                cwd, ToolHooks.POST);
        assertEquals("lint 没过", block.after("edit_file", "{}", "已修改", true));

        ToolHooks ctx = ToolHooks.parse(M.readTree("""
                {"PostToolUse": [{"hooks": [{"command": "cat > post.json; echo '{\\"hookSpecificOutput\\":{\\"additionalContext\\":\\"记得跑测试\\"}}'"}]}]}"""),
                cwd, ToolHooks.POST);
        assertEquals("记得跑测试", ctx.after("bash", "{\"command\":\"ls\"}", "a.txt", true));
        JsonNode seen = M.readTree(cwd.resolve("post.json").toFile());
        assertEquals("PostToolUse", seen.path("hook_event_name").asText());
        assertEquals("a.txt", seen.path("tool_response").asText(), "工具的输出交给 hook");
    }

    @Test
    @DisplayName("PreToolUse 放行时的 additionalContext 跟着结果交给模型")
    void preToolUseContext(@TempDir Path cwd) throws Exception {
        ToolHooks h = hooks("""
                {"PreToolUse": [{"hooks": [{"command": "echo '{\\"hookSpecificOutput\\":{\\"additionalContext\\":\\"生产库只读\\"}}'"}]}]}""", cwd);
        ToolHooks.Decision d = h.check("bash", "{}");
        assertFalse(d.blocked());
        assertEquals("生产库只读", d.context());
    }

    @Test
    @DisplayName("会话级 hook 从家目录和工作区的 settings.json 读，两份合并（照 Claude Code）")
    void sessionHooksLoadBothSettings(@TempDir Path tmp) throws Exception {
        Path home = java.nio.file.Files.createDirectories(tmp.resolve("home/.myagent"));
        Path ws = java.nio.file.Files.createDirectories(tmp.resolve("ws/.myagent"));
        java.nio.file.Files.writeString(home.resolve("settings.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [{"command": "exit 2"}]}]}}""");
        java.nio.file.Files.writeString(ws.resolve("settings.json"), """
                {"hooks": {"PostToolUse": [{"hooks": [{"command": "echo hi >&2; exit 2"}]}]}}""");
        SessionHooks s = SessionHooks.load(home.resolve("settings.json"), tmp.resolve("ws"));
        assertTrue(s.pre().check("bash", "{}").blocked());
        assertEquals("hi", s.post().after("read_file", "{}", "x", true));
        assertTrue(SessionHooks.NONE.isEmpty());
        assertFalse(s.isEmpty());
    }

    // ---------- PermissionRequest / PermissionDenied ----------

    @Test
    @DisplayName("PermissionRequest（照 Claude Code）：decision.behavior allow / deny 替人回答；退出码 2 不算数；没说就交给人")
    void permissionRequestAnswers(@TempDir Path cwd) throws Exception {
        ToolHooks allow = ToolHooks.parse(M.readTree("""
                {"PermissionRequest": [{"matcher": "Bash", "hooks": [{"command": "cat > perm.json; echo '{\\"hookSpecificOutput\\":{\\"decision\\":{\\"behavior\\":\\"allow\\"}}}'"}]}]}"""),
                cwd, ToolHooks.PERMISSION_REQUEST);
        assertEquals(Boolean.TRUE, allow.permission("bash", "{\"command\":\"mvn test\"}").allow());
        JsonNode seen = M.readTree(cwd.resolve("perm.json").toFile());
        assertEquals("PermissionRequest", seen.path("hook_event_name").asText());
        assertEquals("mvn test", seen.path("tool_input").path("command").asText());
        assertNull(allow.permission("write_file", "{}").allow(), "没对上的不跑");

        ToolHooks deny = ToolHooks.parse(M.readTree("""
                {"PermissionRequest": [{"hooks": [{"command": "echo '{\\"hookSpecificOutput\\":{\\"decision\\":{\\"behavior\\":\\"deny\\",\\"message\\":\\"周五不发版\\"}}}'"}]}]}"""),
                cwd, ToolHooks.PERMISSION_REQUEST);
        ToolHooks.Answer no = deny.permission("bash", "{}");
        assertEquals(Boolean.FALSE, no.allow());
        assertEquals("周五不发版", no.message());

        ToolHooks two = ToolHooks.parse(M.readTree("""
                {"PermissionRequest": [{"hooks": [{"command": "exit 2"}]}]}"""), cwd, ToolHooks.PERMISSION_REQUEST);
        assertNull(two.permission("bash", "{}").allow(), "PermissionRequest 不认退出码 2（照 Claude Code），交给人");
    }

    @Test
    @DisplayName("PermissionDenied（照 Claude Code）：auto 审查拒了之后跑；hookSpecificOutput.retry=true 表示可以重试；退出码不算数")
    void permissionDeniedRetry(@TempDir Path cwd) throws Exception {
        ToolHooks retry = ToolHooks.parse(M.readTree("""
                {"PermissionDenied": [{"hooks": [{"command": "cat > denied.json; echo '{\\"hookSpecificOutput\\":{\\"retry\\":true}}'"}]}]}"""),
                cwd, ToolHooks.PERMISSION_DENIED);
        assertTrue(retry.retry("bash", "{\"command\":\"curl x\"}", "外传数据"));
        JsonNode seen = M.readTree(cwd.resolve("denied.json").toFile());
        assertEquals("PermissionDenied", seen.path("hook_event_name").asText());
        assertEquals("外传数据", seen.path("reason").asText());

        ToolHooks quiet = ToolHooks.parse(M.readTree("""
                {"PermissionDenied": [{"hooks": [{"command": "exit 2"}]}]}"""), cwd, ToolHooks.PERMISSION_DENIED);
        assertFalse(quiet.retry("bash", "{}", "x"));
    }

    @Test
    @DisplayName("审批请求换成 hook 认得的工具名和参数：bash→Bash{command}、写→Write{file_path}、读→Read{file_path}、联网→{host}")
    void approvalRequestsLookLikeToolCalls(@TempDir Path cwd) throws Exception {
        java.util.List<String> asked = new java.util.ArrayList<>();
        dev.wiles.myagent.tool.safety.ApprovalPolicy human = r -> {
            asked.add(r.subject());
            return dev.wiles.myagent.tool.safety.ApprovalVerdict.yes();
        };
        ToolHooks perm = ToolHooks.parse(M.readTree("""
                {"PermissionRequest": [{"matcher": "Write", "hooks": [{"command": "input=$(cat); case \\"$input\\" in *secrets*) echo '{\\"hookSpecificOutput\\":{\\"decision\\":{\\"behavior\\":\\"deny\\",\\"message\\":\\"不许写\\"}}}';; esac"}]}]}"""),
                cwd, ToolHooks.PERMISSION_REQUEST);
        SessionHooks s = new SessionHooks(ToolHooks.NONE, ToolHooks.NONE, perm, ToolHooks.NONE);
        dev.wiles.myagent.tool.safety.ApprovalPolicy chain = s.askHooksFirst(human);

        var v = chain.decide(dev.wiles.myagent.tool.safety.ApprovalRequest.write("写入文件", "secrets/a.txt", "覆盖", ""));
        assertFalse(v.approved());
        assertTrue(v.deniedMessage("").contains("不许写"), v.deniedMessage(""));
        assertTrue(asked.isEmpty(), "hook 答了就不问人");

        assertTrue(chain.decide(dev.wiles.myagent.tool.safety.ApprovalRequest.write("写入文件", "src/a.txt", "覆盖", "")).approved());
        assertTrue(chain.decide(dev.wiles.myagent.tool.safety.ApprovalRequest.bash("ls secrets", "")).approved(),
                "matcher 是 Write，bash 不跑这个 hook");
        assertEquals(java.util.List.of("src/a.txt", "ls secrets"), asked, "hook 没说的照常问人");
    }
}
