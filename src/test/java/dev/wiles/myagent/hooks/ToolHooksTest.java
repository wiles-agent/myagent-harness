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
}
