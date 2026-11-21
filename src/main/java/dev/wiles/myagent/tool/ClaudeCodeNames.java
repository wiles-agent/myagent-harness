package dev.wiles.myagent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claude Code 的工具名和我们的对照。两处要用：agent 定义里的 {@code tools: Read, Grep}（翻成我们的名字），
 * 子 agent 的 PreToolUse hook（{@code matcher: Bash}、stdin 里的 {@code tool_name}，翻成 Claude Code 的名字）。
 * 只有一份，两处不会对不上。
 */
public final class ClaudeCodeNames {

    /** Claude Code 的名字 → 我们的。 */
    public static final Map<String, String> TO_OURS = Map.of(
            "Read", "read_file", "Write", "write_file", "Edit", "edit_file", "MultiEdit", "edit_file",
            "Grep", "grep", "Glob", "glob", "Bash", "bash", "LS", "list_dir",
            "WebFetch", "web_fetch", "WebSearch", "web_search");

    /** 我们的 → Claude Code 的（edit_file 取 Edit，不取 MultiEdit）。 */
    public static final Map<String, String> TO_CLAUDE_CODE;

    static {
        Map<String, String> reverse = new LinkedHashMap<>();
        TO_OURS.forEach((cc, ours) -> {
            if (!"MultiEdit".equals(cc)) {
                reverse.put(ours, cc);
            }
        });
        TO_CLAUDE_CODE = Map.copyOf(reverse);
    }

    private ClaudeCodeNames() {
    }

    /** 给 hook 看的名字：有 Claude Code 的叫法就用它，没有（MCP 工具、我们独有的）就原样。 */
    public static String forHooks(String ours) {
        return TO_CLAUDE_CODE.getOrDefault(ours, ours);
    }
}
