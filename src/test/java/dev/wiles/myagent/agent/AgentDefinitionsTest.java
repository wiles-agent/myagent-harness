package dev.wiles.myagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** agent 类型的定义文件：解析、工具名单的两道、搜索路径与覆盖顺序。 */
class AgentDefinitionsTest {

    private static final List<String> TOOLS = List.of(
            "read_file", "write_file", "edit_file", "bash", "grep", "mcp__chrome__click", "mcp__chrome__fill");

    @Test
    @DisplayName("内置：default 全给、explorer 去掉写文件、worker 要 owns，外加 fork；顺序固定；fork 是保留名")
    void builtins() {
        AgentDefinitions d = AgentDefinitions.builtins();
        assertEquals(List.of("default", "explorer", "worker", "fork"), d.all().stream().map(AgentDefinition::name).toList());
        AgentDefinitions custom = AgentDefinitions.of(List.of(new AgentDefinition("fork", "冒名", null, List.of(), "", false, "t")));
        assertSame(AgentDefinitions.FORK, custom.get("fork"));
        assertEquals(TOOLS, List.copyOf(d.get("default").resolve(TOOLS)));
        assertFalse(d.get("explorer").resolve(TOOLS).contains("write_file"));
        assertTrue(d.get("explorer").resolve(TOOLS).contains("bash"));
        assertTrue(d.get("worker").ownsRequired());
        assertFalse(d.get("explorer").rules().isBlank());
    }

    @Test
    @DisplayName("Claude Code 的写法照收：Read/Grep 翻成我们的名字，Bash(git *) 取工具名，YAML 列表也认")
    void parsesClaudeCodeStyleFiles() {
        AgentDefinition a = AgentDefinition.parse(Path.of("x/Code-Reviewer.md"), """
                ---
                description: 审查改动
                tools: Read, Grep, Bash(git diff *)
                disallowedTools: Bash
                ---
                只看不改。
                """);
        assertEquals("code-reviewer", a.name());
        assertEquals(List.of("read_file", "grep", "bash"), a.tools());
        assertEquals(List.of("read_file", "grep"), List.copyOf(a.resolve(TOOLS)));
        assertEquals("只看不改。", a.rules());

        AgentDefinition b = AgentDefinition.parse(Path.of("b.md"), """
                ---
                name: Browser
                description: 用浏览器点页面
                tools:
                  - Read
                  - mcp__chrome__*
                owns: required
                ---
                """);
        assertEquals("browser", b.name());
        assertNull(b.model(), "不写 model 就跟主对话一样");
        assertEquals("deepseek-v4.1-flash", AgentDefinition.parse(Path.of("m.md"),
                "---\ndescription: x\nmodel: deepseek-v4.1-flash\n---\n").model());
        assertNull(AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmodel: inherit\n---\n").model());
        assertNull(AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmodel: a b; rm\n---\n"),
                "模型名会原样进请求体，不成形的整份作废");
        assertEquals(12, AgentDefinition.parse(Path.of("t.md"), "---\ndescription: x\nmaxTurns: 12\n---\n").maxTurns());
        assertEquals(0, b.maxTurns(), "不写就是 0：跟会话的默认走");
        assertNull(AgentDefinition.parse(Path.of("t.md"), "---\ndescription: x\nmaxTurns: lots\n---\n"));
        assertNull(AgentDefinition.parse(Path.of("t.md"), "---\ndescription: x\nmaxTurns: -3\n---\n"));
        assertEquals(List.of("read_file", "mcp__chrome__click", "mcp__chrome__fill"), List.copyOf(b.resolve(TOOLS)));
        assertTrue(b.ownsRequired());
        assertEquals(List.of(), b.unmatched(TOOLS));
    }

    @Test
    @DisplayName("坏定义只作废它自己：没写描述、名字不成形、通配写在 mcp__ 之外（b* 会把 bash 递出去）")
    void badDefinitionsAreDropped() {
        assertNull(AgentDefinition.parse(Path.of("a.md"), "---\nname: a\n---\n正文"));
        assertNull(AgentDefinition.parse(Path.of("a.md"), "---\nname: 审查\ndescription: x\n---\n"));
        assertNull(AgentDefinition.parse(Path.of("a.md"), "---\ndescription: x\ntools: b*\n---\n"));
        assertNull(AgentDefinition.parse(Path.of("a.md"), "---\ndescription: x\ndisallowedTools: *\n---\n"));
        assertNotNull(AgentDefinition.parse(Path.of("a.md"), "---\ndescription: x\ntools:\n---\n"));
    }

    @Test
    @DisplayName("写错的工具名报得出来；tools 留空等于继承")
    void unmatchedToolsAreReported() {
        AgentDefinition a = new AgentDefinition("a", "x", List.of("read_file", "reed_file"), List.of(), "", false, "t");
        assertEquals(List.of("reed_file"), a.unmatched(TOOLS));
        AgentDefinition inherit = AgentDefinition.parse(Path.of("a.md"), "---\ndescription: x\ntools:\n---\n");
        assertNull(inherit.tools());
        assertEquals(TOOLS, List.copyOf(inherit.resolve(TOOLS)));
    }

    @Test
    @DisplayName("搜索路径：工作区压家目录、.myagent 压 .claude、都压内置；自定义按名字排在内置后面")
    void loadOrderAndOverrides(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Path home = tmp.resolve("home");
        write(home.resolve(".claude/agents/zeta.md"), "家里 claude 的 zeta");
        write(home.resolve(".myagent/agents/zeta.md"), "家里 myagent 的 zeta");
        write(ws.resolve(".claude/agents/alpha.md"), "工作区的 alpha");
        write(ws.resolve(".myagent/agents/worker.md"), "改过的 worker");
        Files.writeString(ws.resolve(".myagent/agents/notes.txt"), "不是 md，不认");

        AgentDefinitions d = AgentDefinitions.load(AgentDefinitions.searchPath(ws, home));
        assertEquals(List.of("default", "explorer", "worker", "fork", "alpha", "zeta"),
                d.all().stream().map(AgentDefinition::name).toList());
        assertEquals("家里 myagent 的 zeta", d.get("zeta").description());
        assertEquals("改过的 worker", d.get("worker").description());
        assertFalse(d.get("worker").ownsRequired(), "覆盖内置的不继承 owns: required，要自己写");
    }

    private static void write(Path file, String description) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ndescription: " + description + "\n---\n规矩\n");
    }
}
