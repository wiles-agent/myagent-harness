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

        // permissionMode 只收「收紧」的三种：项目目录里的定义不能借它给子 agent 放宽权限
        assertEquals(AgentDefinition.PermissionMode.DEFAULT, b.permissionMode());
        assertEquals(AgentDefinition.PermissionMode.PLAN,
                AgentDefinition.parse(Path.of("p.md"), "---\ndescription: x\npermissionMode: plan\n---\n").permissionMode());
        assertEquals(AgentDefinition.PermissionMode.DONT_ASK,
                AgentDefinition.parse(Path.of("p.md"), "---\ndescription: x\npermissionMode: dontAsk\n---\n").permissionMode());
        assertEquals(AgentDefinition.PermissionMode.DEFAULT,
                AgentDefinition.parse(Path.of("p.md"), "---\ndescription: x\npermissionMode: manual\n---\n").permissionMode());
        assertNull(b.hooks());
        AgentDefinition hooked = AgentDefinition.parse(Path.of("h.md"), """
                ---
                description: 只读查库
                hooks:
                  PreToolUse:
                    - matcher: "Bash"
                      hooks:
                        - type: command
                          command: "./scripts/validate-readonly-query.sh"
                ---
                """);
        assertEquals("Bash", hooked.hooks().path("PreToolUse").get(0).path("matcher").asText());
        assertNull(AgentDefinition.parse(Path.of("h.md"),
                        "---\ndescription: x\nhooks:\n  PreToolUse:\n    - matcher: \"(\"\n      hooks:\n        - command: x\n---\n"),
                "matcher 写坏了：写了 hook 却不生效比没写更糟，整份作废");
        assertNull(b.effort());
        for (String e : List.of("low", "medium", "high", "xhigh", "max")) {
            assertEquals(e, AgentDefinition.parse(Path.of("e.md"), "---\ndescription: x\neffort: " + e + "\n---\n").effort());
        }
        assertNull(AgentDefinition.parse(Path.of("e.md"), "---\ndescription: x\neffort: extreme\n---\n"));
        assertNull(b.color());
        assertEquals("cyan", AgentDefinition.parse(Path.of("c.md"), "---\ndescription: x\ncolor: cyan\n---\n").color());
        assertNull(AgentDefinition.parse(Path.of("c.md"), "---\ndescription: x\ncolor: '#ff0000'\n---\n"),
                "只认 Claude Code 那 8 种颜色");
        assertFalse(b.background());
        assertTrue(AgentDefinition.parse(Path.of("g.md"), "---\ndescription: x\nbackground: true\n---\n").background());
        assertNull(AgentDefinition.parse(Path.of("g.md"), "---\ndescription: x\nbackground: maybe\n---\n"));
        assertEquals(AgentDefinition.Memory.NONE, b.memory());
        assertEquals(AgentDefinition.Memory.PROJECT,
                AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmemory: project\n---\n").memory());
        assertEquals(AgentDefinition.Memory.PROJECT,
                AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmemory: local\n---\n").memory());
        assertEquals(AgentDefinition.Memory.USER,
                AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmemory: user\n---\n").memory());
        assertNull(AgentDefinition.parse(Path.of("m.md"), "---\ndescription: x\nmemory: everything\n---\n"));
        assertEquals(List.of(), b.skills());
        assertEquals(List.of("deploy", "code-review"), AgentDefinition.parse(Path.of("s.md"),
                "---\ndescription: x\nskills: deploy, code-review\n---\n").skills());
        assertEquals(List.of("deploy", "code-review"), AgentDefinition.parse(Path.of("s.md"),
                "---\ndescription: x\nskills:\n  - deploy\n  - code-review\n---\n").skills());
        for (String loose : List.of("acceptEdits", "bypassPermissions", "auto", "whatever")) {
            assertNull(AgentDefinition.parse(Path.of("p.md"), "---\ndescription: x\npermissionMode: " + loose + "\n---\n"),
                    loose + " 会放宽权限或不认识，整份作废");
        }
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

    private static final String WITH_MCP = """
            ---
            description: 用浏览器
            mcpServers:
              - github
              - playwright:
                  type: stdio
                  command: npx
                  args: ["-y", "@playwright/mcp@latest"]
            ---
            """;

    @Test
    @DisplayName("mcpServers：名字引用哪儿都能写；内联定义要起进程，只认家目录里的定义，工作区里写了整份作废")
    void mcpServersRefsAndInline() {
        AgentDefinition user = AgentDefinition.parse(Path.of("u.md"), WITH_MCP, true);
        assertEquals(List.of("github"), user.mcpRefs());
        assertEquals("npx", user.mcpInline().get("playwright").path("command").asText());
        assertEquals("@playwright/mcp@latest", user.mcpInline().get("playwright").path("args").get(1).asText());

        assertNull(AgentDefinition.parse(Path.of("p.md"), WITH_MCP, false), "工作区的定义不能借子 agent 起进程");
        assertNull(AgentDefinition.parse(Path.of("p.md"), WITH_MCP), "默认按工作区算，宁紧勿松");
        AgentDefinition refsOnly = AgentDefinition.parse(Path.of("p.md"),
                "---\ndescription: x\nmcpServers:\n  - github\n---\n", false);
        assertEquals(List.of("github"), refsOnly.mcpRefs());
        assertTrue(refsOnly.mcpInline().isEmpty());
        assertNull(AgentDefinition.parse(Path.of("u.md"),
                "---\ndescription: x\nmcpServers:\n  - broken:\n      args: [\"x\"]\n---\n", true), "内联的没写 command");
    }

    @Test
    @DisplayName("加载：家目录里的定义能内联 MCP，工作区里的不能")
    void inlineMcpOnlyFromHome(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Path home = tmp.resolve("home");
        Files.createDirectories(home.resolve(".myagent/agents"));
        Files.writeString(home.resolve(".myagent/agents/browser.md"), WITH_MCP);
        Files.createDirectories(ws.resolve(".myagent/agents"));
        Files.writeString(ws.resolve(".myagent/agents/sneaky.md"), WITH_MCP);

        AgentDefinitions d = AgentDefinitions.load(ws, home);
        assertNotNull(d.get("browser"));
        assertNull(d.get("sneaky"));
    }

    @Test
    @DisplayName("无头模式：默认只用内置类型（评测不受本机定义影响）；MYAGENT_AGENTS=on 才读工作区和家目录的定义")
    void headlessLoadsCustomTypesOnlyWhenAsked(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Path home = tmp.resolve("home");
        write(ws.resolve(".myagent/agents/scout.md"), "查东西");

        AgentDefinitions off = AgentDefinitions.forHeadless(java.util.Map.<String, String>of()::get, ws, home);
        assertNull(off.get("scout"));
        AgentDefinitions on = AgentDefinitions.forHeadless(java.util.Map.of("MYAGENT_AGENTS", "on")::get, ws, home);
        assertEquals("查东西", on.get("scout").description());
        assertNotNull(on.get("worker"));
    }

    @Test
    @DisplayName("描述预算：只算自定义类型；超过 15000 token 要报出来，但照样全部加载")
    void descriptionBudget() {
        assertEquals(0, AgentDefinitions.builtins().customDescriptionTokens(), "内置的不算");
        assertFalse(AgentDefinitions.builtins().overDescriptionBudget());

        AgentDefinitions small = AgentDefinitions.of(List.of(
                new AgentDefinition("a", "查东西", null, List.of(), "", false, "t")));
        assertTrue(small.customDescriptionTokens() > 0);
        assertFalse(small.overDescriptionBudget());

        String huge = "用来处理一类非常具体的任务，".repeat(3000);
        AgentDefinitions big = AgentDefinitions.of(List.of(
                new AgentDefinition("a", huge, null, List.of(), "", false, "t"),
                new AgentDefinition("b", huge, null, List.of(), "", false, "t")));
        assertTrue(big.customDescriptionTokens() > AgentDefinitions.DESCRIPTION_BUDGET_TOKENS);
        assertTrue(big.overDescriptionBudget());
        assertNotNull(big.get("a"), "超预算只是提醒，不丢定义");
        assertNotNull(big.get("b"));
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
