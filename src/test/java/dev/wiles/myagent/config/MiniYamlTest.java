package dev.wiles.myagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** frontmatter 里嵌套字段（hooks、mcpServers）用到的那一小块 YAML。 */
class MiniYamlTest {

    private static final String DB_READER = """
            ---
            name: db-reader
            description: Execute read-only database queries.
            tools: Bash
            hooks:
              PreToolUse:
                # 只拦 Bash
                - matcher: "Bash"
                  hooks:
                    - type: command
                      command: "./scripts/validate-readonly-query.sh"
                      timeout: 30
            mcpServers:
              - github
              - playwright:
                  type: stdio
                  command: npx
                  args: ["-y", "@playwright/mcp@latest"]
            ---
            You are a database analyst with read-only access.
            """;

    @Test
    @DisplayName("Claude Code 文档里的 db-reader：hooks 读成和 settings.json 一样的结构")
    void readsClaudeCodeHooksBlock() {
        JsonNode hooks = MiniYaml.frontMatterField(DB_READER, "hooks");
        JsonNode group = hooks.path("PreToolUse").get(0);
        assertEquals("Bash", group.path("matcher").asText());
        JsonNode hook = group.path("hooks").get(0);
        assertEquals("command", hook.path("type").asText());
        assertEquals("./scripts/validate-readonly-query.sh", hook.path("command").asText());
        assertEquals(30, hook.path("timeout").asInt());
        assertTrue(hook.path("timeout").isInt(), "数字读成数字");
    }

    @Test
    @DisplayName("列表里混着字符串和映射，行内的 JSON 数组照读")
    void readsMixedListsAndFlowArrays() {
        JsonNode servers = MiniYaml.frontMatterField(DB_READER, "mcpServers");
        assertEquals("github", servers.get(0).asText());
        JsonNode playwright = servers.get(1).path("playwright");
        assertEquals("npx", playwright.path("command").asText());
        assertEquals("-y", playwright.path("args").get(0).asText());
        assertEquals("@playwright/mcp@latest", playwright.path("args").get(1).asText());
    }

    @Test
    @DisplayName("没有这个字段是 null；值写在同一行的是标量；整段写成 JSON 也认；不是 frontmatter 是 null")
    void edgeCases() {
        assertNull(MiniYaml.frontMatterField(DB_READER, "memory"));
        assertEquals("Bash", MiniYaml.frontMatterField(DB_READER, "tools").asText());
        String json = "---\ndescription: x\nhooks: {\"PreToolUse\": [{\"hooks\": [{\"command\": \"exit 2\"}]}]}\n---\n";
        assertEquals("exit 2", MiniYaml.frontMatterField(json, "hooks").path("PreToolUse").get(0)
                .path("hooks").get(0).path("command").asText());
        assertNull(MiniYaml.frontMatterField("没有头的正文", "hooks"));
        assertTrue(MiniYaml.frontMatterField("---\nflag: true\n---\n", "flag").isBoolean());
        assertEquals("it's", MiniYaml.frontMatterField("---\nq: 'it''s'\n---\n", "q").asText());
    }

    @Test
    @DisplayName("缩进对不齐的写坏了要报出来，不能悄悄读成别的结构")
    void malformedIndentationThrows() {
        String bad = """
                ---
                hooks:
                  PreToolUse:
                    - matcher: Bash
                   hooks: oops
                ---
                """;
        assertThrows(IllegalArgumentException.class, () -> MiniYaml.frontMatterField(bad, "hooks"));
    }
}
