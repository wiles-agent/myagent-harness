package dev.wiles.myagent.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.tool.ClaudeCodeNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具调用前跑的 hook，照 Claude Code 的 {@code PreToolUse}。现在用在子 agent 的类型定义上
 * （{@code agents/*.md} 的 {@code hooks}），只管那一种子 agent。
 *
 * <h3>为什么要有它</h3>
 * {@code tools} 名单只能按整个工具给或不给。「bash 可以用，但只许跑只读的 SQL」这种细一层的规矩，
 * 名单表达不了——Claude Code 的做法就是给那个子 agent 挂一个 PreToolUse hook，看命令再决定（它文档里的 db-reader 例子）。
 *
 * <h3>格式和约定（和 Claude Code 一致，那边写好的直接能用）</h3>
 * <pre>
 * {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "./check.sh", "timeout": 30}]}]}</pre>
 * {@code matcher} 是正则，对工具名整段匹配——Claude Code 的名字（Bash、Edit）和我们的（bash、edit_file）都认；
 * 不写或写 {@code *} 是全部。stdin 收到 {@code {"hook_event_name":"PreToolUse","tool_name","tool_input","cwd"}}，
 * {@code tool_name} 给 Claude Code 的叫法，我们的叫法在 {@code myagent_tool_name}。
 * <ul>
 *   <li>退出码 2：拦下，stderr 是给模型的理由</li>
 *   <li>退出码 0：放行；stdout 是 JSON 时认 {@code hookSpecificOutput.permissionDecision = deny} 和 {@code decision = block}</li>
 *   <li>别的退出码、超时、起不来：不拦，记一条警告——hook 自己坏了不该把 agent 卡住（和 {@link PromptHooks} 同一条纪律）</li>
 * </ul>
 */
public final class ToolHooks {

    private static final Logger log = LoggerFactory.getLogger(ToolHooks.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String EVENT = "PreToolUse";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 一组：matcher（null 表示全部）和它下面的命令。 */
    record Group(Pattern matcher, List<PromptHooks.Command> commands) {
        boolean matches(String ours) {
            return matcher == null || matcher.matcher(ours).matches()
                    || matcher.matcher(ClaudeCodeNames.forHooks(ours)).matches();
        }
    }

    /**
     * @param blocked  拦下了吗；拦下时 {@code reason} 是给模型的理由
     * @param warnings hook 自己出的问题
     */
    public record Decision(boolean blocked, String reason, List<String> warnings) {
        static final Decision PASS = new Decision(false, "", List.of());
    }

    public static final ToolHooks NONE = new ToolHooks(List.of(), Path.of("."));

    private final List<Group> groups;
    private final Path cwd;

    ToolHooks(List<Group> groups, Path cwd) {
        this.groups = List.copyOf(groups);
        this.cwd = cwd;
    }

    /**
     * 从 Claude Code 格式的 hooks 对象里读 PreToolUse（别的事件忽略）。matcher 写坏了抛
     * {@link IllegalArgumentException}——调用方据此作废整份定义：写了 hook 却没生效，比没写更糟。
     */
    public static ToolHooks parse(JsonNode hooks, Path cwd) {
        List<Group> groups = new ArrayList<>();
        for (JsonNode group : hooks.path(EVENT)) {
            String raw = group.path("matcher").asText("").strip();
            Pattern matcher;
            try {
                matcher = raw.isEmpty() || "*".equals(raw) ? null : Pattern.compile(raw);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("PreToolUse 的 matcher 不是合法的正则: " + raw);
            }
            List<PromptHooks.Command> commands = new ArrayList<>();
            for (JsonNode hook : group.path("hooks")) {
                String type = hook.path("type").asText("command");
                String command = hook.path("command").asText("").strip();
                if (!"command".equals(type) || command.isEmpty()) {
                    continue;
                }
                int timeout = hook.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
                commands.add(new PromptHooks.Command(command, timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS, null));
            }
            if (!commands.isEmpty()) {
                groups.add(new Group(matcher, commands));
            }
        }
        return new ToolHooks(groups, cwd);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** 调 {@code tool} 之前问一遍。对上的组依次跑，一个拦下就不往下跑了。 */
    public Decision check(String tool, String argumentsJson) {
        if (groups.isEmpty()) {
            return Decision.PASS;
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("hook_event_name", EVENT);
        input.put("tool_name", ClaudeCodeNames.forHooks(tool));
        input.put("myagent_tool_name", tool);
        try {
            input.set("tool_input", JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson));
        } catch (IOException e) {
            input.put("tool_input", argumentsJson);
        }
        input.put("cwd", String.valueOf(cwd));
        byte[] stdin = input.toString().getBytes(StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        for (Group group : groups) {
            if (!group.matches(tool)) {
                continue;
            }
            for (PromptHooks.Command command : group.commands()) {
                HookRunner.Result r = HookRunner.run(command.command(), command.timeoutSeconds(), cwd, stdin);
                if (r.error() != null) {
                    warnings.add("hook「" + command.command() + "」" + r.error());
                    continue;
                }
                if (r.exit() == 2) {
                    return new Decision(true, r.stderr().isBlank()
                            ? "被 hook「" + command.command() + "」拦下了" : r.stderr().strip(), warnings);
                }
                if (r.exit() != 0) {
                    warnings.add("hook「" + command.command() + "」退出码 " + r.exit()
                            + (r.stderr().isBlank() ? "" : ": " + r.stderr().strip()));
                    continue;
                }
                String denied = denied(r.stdout().strip());
                if (denied != null) {
                    return new Decision(true, denied.isEmpty() ? "被 hook「" + command.command() + "」拦下了" : denied,
                            warnings);
                }
            }
        }
        warnings.forEach(w -> log.warn("{}", w));
        return new Decision(false, "", warnings);
    }

    /** stdout 是表示拒绝的 JSON 时返回理由（可能是空串），否则 null。 */
    private static String denied(String stdout) {
        if (!stdout.startsWith("{")) {
            return null;
        }
        try {
            JsonNode out = JSON.readTree(stdout);
            JsonNode specific = out.path("hookSpecificOutput");
            if ("deny".equals(specific.path("permissionDecision").asText())) {
                return specific.path("permissionDecisionReason").asText("").strip();
            }
            if ("block".equals(out.path("decision").asText())) {
                return out.path("reason").asText("").strip();
            }
        } catch (IOException e) {
            // 不是 JSON 就不是决定
        }
        return null;
    }
}
