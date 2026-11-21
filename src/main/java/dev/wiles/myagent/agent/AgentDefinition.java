package dev.wiles.myagent.agent;

import dev.wiles.myagent.skill.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一种子 agent：名字、什么时候派它、它手上有哪些工具、它要守的规矩。照 Claude Code 的 subagent 定义做——
 * 类型是<b>数据</b>，不是写死在代码里的角色。内置的 default / explorer / worker 也只是三份定义
 * （见 {@link AgentDefinitions#builtins()}；fork 是另一回事，它是带着整段对话的派法），用户可以在 {@code agents/*.md} 里再加。
 *
 * <h3>工具怎么算</h3>
 * 和 Claude Code 一样两道：先从主 agent 的全量菜单里拿掉 {@code disallowedTools}，
 * 再在剩下的里面按 {@code tools} 白名单挑；{@code tools} 没写就是剩下的全部。
 * 两道都只会<b>收窄</b>——定义给不出主 agent 没有的工具，也绕不过审批（工具本身还是那一个）。
 * 所以项目目录里的定义可以放心读：它最多让子 agent 少几样工具，不会多授权什么。
 *
 * <h3>通配只对 MCP 开放</h3>
 * {@code mcp__chrome__*} 这样的尾部通配是必要的——MCP 工具名里 server 那一截是用户在 mcp.json 里自己起的。
 * 别的位置不许出现 {@code *}：工具名单是安全边界，一个手滑的 {@code "b*"} 就能把 bash 递给只读的类型。
 *
 * @param name            派的时候写的 {@code agent_type}
 * @param description     写进 spawn_agent 说明里的那段：干什么、什么时候派
 * @param tools           白名单；null 表示继承（拿掉黑名单之后的全部）
 * @param disallowedTools 黑名单，先于白名单生效
 * @param rules           这种 agent 的规矩，拼进它自己的系统提示词（见 {@code SystemPrompt#forSubagent}）
 * @param ownsRequired    派它必须写 {@code owns}（写入归属）。内置的 worker 是
 * @param source          哪来的：{@code builtin} 或文件路径。日志和排查用
 * @param model           这种子 agent 用的模型；null（或文件里写 {@code inherit}）表示跟主对话一样。
 *                        照 Claude Code：把简单的活交给同一个服务上更便宜的模型，是子 agent 控制成本的那条路
 * @param maxTurns        这种子 agent 一轮最多走几步；0 表示跟会话的默认（{@code MYAGENT_SUBAGENT_STEPS}）。
 *                        照 Claude Code 的 {@code maxTurns}：撞线交回的是 partial，能用 send_input 接着做
 * @param permissionMode  这种子 agent 的权限模式，见 {@link PermissionMode}
 */
public record AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                              String rules, boolean ownsRequired, String source, String model, int maxTurns,
                              PermissionMode permissionMode) {

    /**
     * 照 Claude Code 的 {@code permissionMode}，但<b>只收收紧的三种</b>。
     *
     * <p>Claude Code 还有 acceptEdits / bypassPermissions / auto——都是放宽。我们会读工作区里的
     * {@code .myagent/agents/}，一个 PR 往仓库里加一份 bypassPermissions 的定义，下一个在这儿跑 agent 的人
     * 就已经给子 agent 开了绕过审批的口子（Claude Code 对插件里的子 agent 也是出于安全考虑直接忽略这个字段）。
     * 所以写了放宽的模式，整份定义作废，而不是悄悄降级成 default——悄悄降级的话写的人以为生效了。
     */
    public enum PermissionMode {
        /** 照常审批。{@code manual} 是它的别名。 */
        DEFAULT,
        /** 始终只读：只拿得到 plan 模式下的那些工具，不管主对话在不在 plan 模式。 */
        PLAN,
        /** 要问人的操作一律拒绝、不弹框；被规则放行的照常做。适合在后台跑、不该打扰人的子 agent。 */
        DONT_ASK;

        static PermissionMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DEFAULT;
            }
            return switch (raw.strip()) {
                case "default", "manual" -> DEFAULT;
                case "plan" -> PLAN;
                case "dontAsk" -> DONT_ASK;
                case "acceptEdits", "bypassPermissions", "auto" -> throw new IllegalArgumentException(
                        "permissionMode: " + raw.strip() + " 会放宽权限，不支持（项目目录里的定义不能借它绕过审批）。"
                                + "只认 default / plan / dontAsk");
                default -> throw new IllegalArgumentException("permissionMode 只认 default / plan / dontAsk，收到: " + raw);
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AgentDefinition.class);

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * Claude Code 的工具名到我们的。{@code ~/.claude/agents/} 下现成的定义写的是 {@code Read, Grep}，
     * 不翻译的话白名单一个都对不上，子 agent 会拿着空菜单出门。
     */
    static final Map<String, String> CLAUDE_CODE_NAMES = Map.of(
            "Read", "read_file", "Write", "write_file", "Edit", "edit_file", "MultiEdit", "edit_file",
            "Grep", "grep", "Glob", "glob", "Bash", "bash", "LS", "list_dir",
            "WebFetch", "web_fetch", "WebSearch", "web_search");

    public AgentDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("agent 类型名只能用小写字母、数字、- 和 _: " + name);
        }
        description = description == null ? "" : description.strip();
        if (description.isEmpty()) {
            throw new IllegalArgumentException(name + " 没写 description，主 agent 不会知道什么时候该派它");
        }
        tools = tools == null ? null : List.copyOf(tools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        for (String pattern : tools == null ? disallowedTools : concat(tools, disallowedTools)) {
            checkPattern(name, pattern);
        }
        rules = rules == null ? "" : rules.strip();
        model = normalizeModel(model);
        if (maxTurns < 0) {
            throw new IllegalArgumentException(name + " 的 maxTurns 不能是负数: " + maxTurns);
        }
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
    }

    public AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                           String rules, boolean ownsRequired, String source, String model, int maxTurns) {
        this(name, description, tools, disallowedTools, rules, ownsRequired, source, model, maxTurns,
                PermissionMode.DEFAULT);
    }

    public AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                           String rules, boolean ownsRequired, String source, String model) {
        this(name, description, tools, disallowedTools, rules, ownsRequired, source, model, 0, PermissionMode.DEFAULT);
    }

    public AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                           String rules, boolean ownsRequired, String source) {
        this(name, description, tools, disallowedTools, rules, ownsRequired, source, null, 0, PermissionMode.DEFAULT);
    }

    private static int turns(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("maxTurns 要一个整数，收到: " + raw);
        }
    }

    /** 空、{@code inherit} 都算「跟主对话一样」。模型名只认常见字符——它会原样进请求体。 */
    static String normalizeModel(String model) {
        if (model == null || model.isBlank() || model.strip().equalsIgnoreCase("inherit")) {
            return null;
        }
        String m = model.strip();
        if (!m.matches("[A-Za-z0-9._:/@-]{1,128}")) {
            throw new IllegalArgumentException("模型名只能用字母、数字和 . _ : / @ -: " + model);
        }
        return m;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void checkPattern(String agent, String pattern) {
        int star = pattern.indexOf('*');
        if (star >= 0 && (star != pattern.length() - 1 || !pattern.startsWith("mcp__"))) {
            throw new IllegalArgumentException(agent + " 的工具名单里 " + pattern
                    + " 不合法：通配只能写在 mcp__ 开头的名字末尾（如 mcp__chrome__*）");
        }
    }

    static boolean matches(String pattern, String tool) {
        return pattern.endsWith("*") ? tool.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(tool);
    }

    /** 从 {@code available}（主 agent 给得出的工具）里算出它的菜单，保持原来的顺序。 */
    public Set<String> resolve(Collection<String> available) {
        Set<String> out = new LinkedHashSet<>();
        for (String tool : available) {
            if (disallowedTools.stream().anyMatch(p -> matches(p, tool))) {
                continue;
            }
            if (tools == null || tools.stream().anyMatch(p -> matches(p, tool))) {
                out.add(tool);
            }
        }
        return out;
    }

    /** 白名单里一个工具都没对上的条目。写错名字的症状是「它就是不用那个工具」，所以得说出来。 */
    public List<String> unmatched(Collection<String> available) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream().filter(p -> available.stream().noneMatch(t -> matches(p, t))).toList();
    }

    /**
     * 读一份 {@code agents/<名字>.md}：YAML 头 + 正文（正文就是它的规矩）。读不成返回 null 并 warn——
     * <b>一份坏定义只作废它自己</b>，和技能、MCP 配置是同一条纪律。
     */
    static AgentDefinition parse(Path file, String text) {
        Skill.Parsed parsed = Skill.split(text);
        Map<String, String> front = parsed.front();
        String stem = file.getFileName().toString().replaceFirst("\\.md$", "");
        String name = front.getOrDefault("name", "").strip();
        name = (name.isEmpty() ? stem : name).toLowerCase(Locale.ROOT);
        try {
            return new AgentDefinition(name, front.get("description"),
                    front.getOrDefault("tools", "").isBlank() ? null : toolList(front.get("tools")),
                    toolList(front.getOrDefault("disallowedtools", "")),
                    parsed.body(),
                    "required".equalsIgnoreCase(front.getOrDefault("owns", "").strip()),
                    file.toString(), front.get("model"), turns(front.get("maxturns")),
                    PermissionMode.parse(front.get("permissionmode")));
        } catch (IllegalArgumentException e) {
            log.warn("agent 定义 {} 作废: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * {@code Read, Grep}、{@code [Read, Grep]}、YAML 列表折叠成的 {@code - Read - Grep} 都认；
     * {@code Bash(git push *)} 这种带范围的写法取工具名——和 Claude Code 一样，名单只按整个工具算。
     */
    static List<String> toolList(String raw) {
        List<String> out = new ArrayList<>();
        String cleaned = raw.replaceAll("\\([^)]*\\)", "").replace("[", " ").replace("]", " ");
        for (String token : cleaned.split("[,\\s]+")) {
            token = token.strip().replaceAll("^[\"']|[\"']$", "");
            if (token.isEmpty() || token.equals("-")) {
                continue;
            }
            out.add(CLAUDE_CODE_NAMES.getOrDefault(token, token));
        }
        return out.stream().distinct().toList();
    }
}
