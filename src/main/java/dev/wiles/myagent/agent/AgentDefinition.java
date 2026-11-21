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
 * @param skills          派出时预载进它系统提示词的技能（按技能 id）。照 Claude Code：只管预载，
 *                        不限制它能调哪些技能
 * @param memory          给不给它长期记忆（{@code recall} / {@code remember}），见 {@link Memory}
 * @param background      照 Claude Code：后台类型，主 agent 要等它时也不阻塞，结果只走通知
 * @param color           界面上显示它用的颜色（照 Claude Code 那 8 种），null 表示界面自己定
 * @param effort          推理力度（照 Claude Code 的 low / medium / high / xhigh / max），null 表示跟主对话一样。
 *                        我们只有一个思考开关：low 关、其余开；服务不认这个开关时不生效，见 {@code SubAgents#thinkingFor}
 * @param hooks           只管这一种子 agent 的 hook，Claude Code 的格式（{@code {"PreToolUse": [...]}}）；null 表示没有。
 *                        支持 PreToolUse（见 {@link dev.wiles.myagent.hooks.ToolHooks}），别的事件忽略并记一笔
 * @param mcpRefs         照 Claude Code 的 {@code mcpServers} 里按名字引用的：会话 mcp.json 里已配的 server，
 *                        它的工具照给这种子 agent（{@code tools} 白名单没列也给）
 * @param mcpInline       {@code mcpServers} 里内联定义的：派出时单独拉起、只给它用。要起进程，只认家目录里的定义
 * @param isolation       照 Claude Code 的 {@code isolation}：{@code worktree} 让它在临时 git worktree 里干活，见 {@link GitWorktree}
 */
public record AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                              String rules, boolean ownsRequired, String source, String model, int maxTurns,
                              PermissionMode permissionMode, List<String> skills, Memory memory,
                              boolean background, String color, String effort,
                              com.fasterxml.jackson.databind.JsonNode hooks,
                              List<String> mcpRefs, Map<String, com.fasterxml.jackson.databind.JsonNode> mcpInline,
                              Isolation isolation) {

    /** 子 agent 在哪儿干活：和大家同一个工作区（默认），或者自己的临时 git worktree。 */
    public enum Isolation {
        NONE, WORKTREE;

        static Isolation parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            if ("worktree".equals(raw.strip())) {
                return WORKTREE;
            }
            throw new IllegalArgumentException("isolation 只认 worktree，收到: " + raw);
        }
    }

    static final Pattern MCP_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    /** Claude Code 认的颜色。界面按名字配色，所以这里只收名字，不收 #rrggbb。 */
    static final Set<String> COLORS = Set.of("red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan");

    /**
     * 照 Claude Code：子 agent <b>不继承</b>长期记忆，定义里写了 {@code memory} 才有。
     *
     * <p>不只是对齐：子 agent 常在读不可信的文件和网页，被带偏之后往用户的长期记忆里写一条，
     * 以后每个会话都会读到它——记忆是跨会话的，污染一次就一直在。所以默认不给，要的时候明着写。
     * 我们没有每个子 agent 单独的记忆目录：USER / PROJECT 都是给它主对话用的那一套 recall / remember，
     * 写的时候照旧由 remember 的 scope 参数决定落在全局还是本项目。Claude Code 的 {@code local} 按 PROJECT 算。
     */
    public enum Memory {
        NONE, USER, PROJECT;

        static Memory parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            return switch (raw.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> NONE;
                case "user" -> USER;
                case "project", "local" -> PROJECT;
                default -> throw new IllegalArgumentException("memory 只认 user / project / local，收到: " + raw);
            };
        }
    }

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
        DONT_ASK,
        /**
         * 照 Claude Code 的 auto：要问人的操作交给自动审查（模型）按用户的意图判，不问人。
         * 它比问人宽——审查可能放过人会拒的——所以<b>只认家目录里的定义</b>，和内联 MCP 同一条信任边界。
         */
        AUTO;

        static PermissionMode parse(String raw, boolean fromHome) {
            if (raw == null || raw.isBlank()) {
                return DEFAULT;
            }
            return switch (raw.strip()) {
                case "default", "manual" -> DEFAULT;
                case "plan" -> PLAN;
                case "dontAsk" -> DONT_ASK;
                case "auto" -> {
                    if (!fromHome) {
                        throw new IllegalArgumentException("permissionMode: auto 比问人宽，只认家目录里的定义"
                                + "（项目目录里的定义不能借它绕过审批）");
                    }
                    yield AUTO;
                }
                case "acceptEdits", "bypassPermissions" -> throw new IllegalArgumentException(
                        "permissionMode: " + raw.strip() + " 会放宽权限，不支持。只认 default / plan / dontAsk / auto");
                default -> throw new IllegalArgumentException(
                        "permissionMode 只认 default / plan / dontAsk / auto，收到: " + raw);
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AgentDefinition.class);

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** Claude Code 的工具名到我们的，见 {@link dev.wiles.myagent.tool.ClaudeCodeNames}。 */
    static final Map<String, String> CLAUDE_CODE_NAMES = dev.wiles.myagent.tool.ClaudeCodeNames.TO_OURS;

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
        skills = skills == null ? List.of() : List.copyOf(skills);
        memory = memory == null ? Memory.NONE : memory;
        color = color == null || color.isBlank() ? null : color.strip().toLowerCase(Locale.ROOT);
        if (color != null && !COLORS.contains(color)) {
            throw new IllegalArgumentException("color 只认 " + String.join(" / ", new java.util.TreeSet<>(COLORS))
                    + "，收到: " + color);
        }
        effort = effort == null || effort.isBlank() ? null : effort.strip().toLowerCase(Locale.ROOT);
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("effort 只认 low / medium / high / xhigh / max，收到: " + effort);
        }
        hooks = hooks == null || hooks.isNull() ? null : hooks;
        if (hooks != null) {
            if (!hooks.isObject()) {
                throw new IllegalArgumentException(name + " 的 hooks 要是一个按事件分组的对象（照 Claude Code 的格式）");
            }
            // 现在就解析一遍：matcher 写坏了在这儿抛，整份定义作废——写了 hook 却没生效比没写更糟
            dev.wiles.myagent.hooks.ToolHooks.parse(hooks, Path.of("."));
            hooks.fieldNames().forEachRemaining(event -> {
                if (!dev.wiles.myagent.hooks.ToolHooks.EVENT.equals(event)) {
                    log.warn("agent 类型 {} 的 hook 事件 {} 不支持（子 agent 只支持 PreToolUse），忽略", name, event);
                }
            });
        }
        mcpRefs = mcpRefs == null ? List.of() : List.copyOf(mcpRefs);
        for (String ref : mcpRefs) {
            if (!MCP_NAME.matcher(ref).matches()) {
                throw new IllegalArgumentException(name + " 的 mcpServers 里 " + ref + " 不是合法的 server 名");
            }
        }
        Map<String, com.fasterxml.jackson.databind.JsonNode> inline = new java.util.LinkedHashMap<>();
        if (mcpInline != null) {
            mcpInline.forEach((server, spec) -> {
                if (!MCP_NAME.matcher(server).matches()) {
                    throw new IllegalArgumentException(name + " 的 mcpServers 里 " + server + " 不是合法的 server 名");
                }
                if (spec == null || spec.path("command").asText("").isBlank()) {
                    throw new IllegalArgumentException(name + " 内联的 MCP server " + server + " 没写 command");
                }
                inline.put(server, spec);
            });
        }
        mcpInline = java.util.Collections.unmodifiableMap(inline);      // 保序：工具菜单的顺序要稳定
        isolation = isolation == null ? Isolation.NONE : isolation;
    }

    /** 只有名字、描述、工具、规矩这几样的常见写法；别的字段取默认。 */
    public AgentDefinition(String name, String description, List<String> tools, List<String> disallowedTools,
                           String rules, boolean ownsRequired, String source) {
        this(name, description, tools, disallowedTools, rules, ownsRequired, source,
                null, 0, PermissionMode.DEFAULT, List.of(), Memory.NONE, false, null, null, null, List.of(), Map.of(),
                Isolation.NONE);
    }

    /**
     * 字段越来越多（照 Claude Code 的 frontmatter），按位置传参已经没法读了——要用哪几项就设哪几项，
     * 其余取默认。默认值和文件里不写那一项是同一个意思。
     */
    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private List<String> tools;
        private List<String> disallowedTools = List.of();
        private String rules = "";
        private boolean ownsRequired;
        private String source = "builtin";
        private String model;
        private int maxTurns;
        private PermissionMode permissionMode = PermissionMode.DEFAULT;
        private List<String> skills = List.of();
        private Memory memory = Memory.NONE;
        private boolean background;
        private String color;
        private String effort;
        private com.fasterxml.jackson.databind.JsonNode hooks;
        private List<String> mcpRefs = List.of();
        private Map<String, com.fasterxml.jackson.databind.JsonNode> mcpInline = Map.of();
        private Isolation isolation = Isolation.NONE;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder tools(List<String> v) { this.tools = v; return this; }
        public Builder disallowedTools(List<String> v) { this.disallowedTools = v; return this; }
        public Builder rules(String v) { this.rules = v; return this; }
        public Builder ownsRequired(boolean v) { this.ownsRequired = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder maxTurns(int v) { this.maxTurns = v; return this; }
        public Builder permissionMode(PermissionMode v) { this.permissionMode = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder memory(Memory v) { this.memory = v; return this; }
        public Builder background(boolean v) { this.background = v; return this; }
        public Builder color(String v) { this.color = v; return this; }
        public Builder effort(String v) { this.effort = v; return this; }
        public Builder hooks(com.fasterxml.jackson.databind.JsonNode v) { this.hooks = v; return this; }
        public Builder mcpRefs(List<String> v) { this.mcpRefs = v; return this; }
        public Builder mcpInline(Map<String, com.fasterxml.jackson.databind.JsonNode> v) { this.mcpInline = v; return this; }
        public Builder isolation(Isolation v) { this.isolation = v; return this; }

        public AgentDefinition build() {
            return new AgentDefinition(name, description, tools, disallowedTools, rules, ownsRequired, source,
                    model, maxTurns, permissionMode, skills, memory, background, color, effort, hooks,
                    mcpRefs, mcpInline, isolation);
        }
    }

    /** 布尔字段：不写是 false；只认 true / false（yes / no 也行），别的整份作废——写错了不能悄悄当成 false。 */
    static boolean flag(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes" -> true;
            case "false", "no" -> false;
            default -> throw new IllegalArgumentException(field + " 只认 true / false，收到: " + raw);
        };
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
        return parse(file, text, false);       // 不知道从哪来的，按工作区算：宁紧勿松
    }

    /**
     * @param fromHome 这份定义在家目录里（用户自己放的）。只有这样的定义能在 mcpServers 里内联启动进程——
     *                 MCP 配置本来就只读家目录的 mcp.json，工作区里的定义不能借子 agent 绕过这条线
     */
    static AgentDefinition parse(Path file, String text, boolean fromHome) {
        Skill.Parsed parsed = Skill.split(text);
        Map<String, String> front = parsed.front();
        String stem = file.getFileName().toString().replaceFirst("\\.md$", "");
        String name = front.getOrDefault("name", "").strip();
        name = (name.isEmpty() ? stem : name).toLowerCase(Locale.ROOT);
        try {
            return builder(name, front.get("description"))
                    .tools(front.getOrDefault("tools", "").isBlank() ? null : toolList(front.get("tools")))
                    .disallowedTools(toolList(front.getOrDefault("disallowedtools", "")))
                    .rules(parsed.body())
                    .ownsRequired("required".equalsIgnoreCase(front.getOrDefault("owns", "").strip()))
                    .source(file.toString())
                    .model(front.get("model"))
                    .maxTurns(turns(front.get("maxturns")))
                    .permissionMode(PermissionMode.parse(front.get("permissionmode"), fromHome))
                    .skills(nameList(front.getOrDefault("skills", "")))
                    .memory(Memory.parse(front.get("memory")))
                    .background(flag("background", front.get("background")))
                    .color(front.get("color"))
                    .effort(front.get("effort"))
                    .hooks(dev.wiles.myagent.config.MiniYaml.frontMatterField(text, "hooks"))
                    .mcpRefs(mcpRefs(dev.wiles.myagent.config.MiniYaml.frontMatterField(text, "mcpServers")))
                    .mcpInline(mcpInline(dev.wiles.myagent.config.MiniYaml.frontMatterField(text, "mcpServers"),
                            fromHome))
                    .isolation(Isolation.parse(front.get("isolation")))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("agent 定义 {} 作废: {}", file, e.getMessage());
            return null;
        }
    }

    /** mcpServers 里按名字引用的那些（列表里的字符串项）。 */
    static List<String> mcpRefs(com.fasterxml.jackson.databind.JsonNode servers) {
        List<String> refs = new ArrayList<>();
        if (servers == null || servers.isNull()) {
            return refs;
        }
        if (!servers.isArray()) {
            throw new IllegalArgumentException("mcpServers 要是一个列表（照 Claude Code 的写法）");
        }
        for (com.fasterxml.jackson.databind.JsonNode item : servers) {
            if (item.isTextual()) {
                refs.add(item.asText().strip());
            } else if (!item.isObject() || item.size() != 1) {
                throw new IllegalArgumentException("mcpServers 的每一项要么是 server 名，要么是「名字: 定义」");
            }
        }
        return refs;
    }

    /** mcpServers 里内联定义的那些（列表里的「名字: 定义」项）。工作区里的定义写了就抛。 */
    static Map<String, com.fasterxml.jackson.databind.JsonNode> mcpInline(com.fasterxml.jackson.databind.JsonNode servers,
                                                                         boolean fromHome) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> inline = new java.util.LinkedHashMap<>();
        if (servers == null || !servers.isArray()) {
            return inline;
        }
        for (com.fasterxml.jackson.databind.JsonNode item : servers) {
            if (item.isObject() && item.size() == 1) {
                String server = item.fieldNames().next();
                inline.put(server, item.get(server));
            }
        }
        if (!inline.isEmpty() && !fromHome) {
            throw new IllegalArgumentException("工作区里的定义不能在 mcpServers 里内联启动 MCP server（" + inline.keySet()
                    + "）：那等于让仓库在别人机器上起进程。配到 ~/.myagent/mcp.json 里，再按名字引用");
        }
        return inline;
    }

    /** 技能名单：和工具名单同样的写法（逗号、方括号、YAML 列表都认），但不做工具名翻译。 */
    static List<String> nameList(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : raw.replace("[", " ").replace("]", " ").split("[,\\s]+")) {
            token = token.strip().replaceAll("^[\"']|[\"']$", "");
            if (!token.isEmpty() && !token.equals("-")) {
                out.add(token);
            }
        }
        return out.stream().distinct().toList();
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
