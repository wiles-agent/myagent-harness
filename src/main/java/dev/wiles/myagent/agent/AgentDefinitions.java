package dev.wiles.myagent.agent;

import dev.wiles.myagent.prompt.PromptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 能派的子 agent 类型：内置的 default / explorer / worker / fork，加上 {@code agents/*.md} 里用户自己写的。照 Claude Code 的 subagent 文件。
 *
 * <h3>去哪找、谁压谁</h3>
 * 和技能（{@code SkillLibrary}）同一个搜索路径、同一个优先级：工作区里的压过家目录里的，
 * {@code .myagent} 压过 {@code .claude}，都压过内置的。同名就是覆盖——想改内置 worker 的规矩，
 * 写一份 {@code worker.md} 就行（覆盖时 {@code owns: required} 要自己写上，不会从内置那份继承）。
 *
 * <h3>顺序</h3>
 * 内置在前，自定义按名字排。这份清单会拼进 spawn_agent 的说明里——顺序一抖，前缀缓存就全程不命中。
 */
public final class AgentDefinitions {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitions.class);
    private static final PromptRepository REPO = PromptRepository.createDefault();

    static final AgentDefinition DEFAULT = new AgentDefinition("default",
            "默认 agent，和你有同样的工具（派子 agent 和长期记忆的除外）。",
            null, List.of(), REPO.loadRequired("subagents/roles/default.md"), false, "builtin");
    static final AgentDefinition EXPLORER = new AgentDefinition("explorer", """
            用来问代码库里具体的问题。explorer 又快又可信，问题要具体、范围要窄。
              - 别让自己去查 explorer 已经查过的东西；一般直接采信它的结论，不用再核实（需要上下文时自己读代码也可以）。
              - 有几个能各自独立回答的问题时，同时派几个 explorer。等结果的时候接着做不依赖这些结果的活——并行是委派最大的好处。
              - 相关的追问用 send_input 交给已有的 explorer，不要新派。
              它没有写文件的工具。""",
            null, List.of("write_file", "edit_file", "create_project", "remember"),
            REPO.loadRequired("subagents/roles/explorer.md"), false, "builtin");
    static final AgentDefinition WORKER = new AgentDefinition("worker", """
            用来干活：实现功能的一部分、修测试或 bug、把大重构拆成独立的几块。
              - 用 owns 明确交代它**负责哪些文件/模块**（所有权，必填），比如「worker 1 负责鉴权模块，worker 2 负责数据库层」。它跑着的时候这些路径只有它能写，你也写不了。
              - 告诉它**不是一个人在改这个代码库**：别人改的不要回退，要调整自己的实现去适配。""",
            null, List.of(), REPO.loadRequired("subagents/roles/worker.md"), true, "builtin");

    /**
     * fork：带着主对话到目前为止的全部历史分出去，系统提示词、工具、模型都和主 agent 一模一样（照 Claude Code 的 fork）。
     * 它是一种<b>派法</b>，不是一种身份——没有规矩、不筛工具，所以名字是保留的，自定义定义不能叫这个。
     */
    static final AgentDefinition FORK = new AgentDefinition("fork", """
            带着你到目前为止的全部对话分出去的分身：系统提示词、工具、模型都和你一样，从这里起各干各的，做完只把最后的结果交回。
              - 别的类型要交代的背景太多时派它；或者想从同一个起点并行试几种做法时，派几个 fork。
              - 它和你共用前缀缓存，比从零开始的子 agent 便宜。
              - 它也能改文件；要并行改代码时同样用 owns 划开写入范围。""",
            null, List.of(), "", false, "builtin");

    private final Map<String, AgentDefinition> byName;

    private AgentDefinitions(Map<String, AgentDefinition> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /** 只有内置的。 */
    public static AgentDefinitions builtins() {
        return of(List.of());
    }

    /** 内置的加上 {@code extra}（后来的同名覆盖先来的；fork 不许覆盖）。 */
    static AgentDefinitions of(List<AgentDefinition> extra) {
        Map<String, AgentDefinition> builtin = new LinkedHashMap<>();
        for (AgentDefinition d : List.of(DEFAULT, EXPLORER, WORKER, FORK)) {
            builtin.put(d.name(), d);
        }
        Map<String, AgentDefinition> custom = new java.util.TreeMap<>();
        for (AgentDefinition d : extra) {
            if (d.name().equals(FORK.name())) {
                log.warn("agent 定义 {} 作废: fork 是保留的名字（它是带着整段对话分身的派法，不是一种类型）", d.source());
                continue;
            }
            AgentDefinition replaced = builtin.containsKey(d.name()) ? builtin.put(d.name(), d) : custom.put(d.name(), d);
            if (replaced != null) {
                log.info("agent 类型 {} 用 {} 的定义（盖过 {}）", d.name(), d.source(), replaced.source());
            }
        }
        Map<String, AgentDefinition> all = new LinkedHashMap<>(builtin);
        all.putAll(custom);
        return new AgentDefinitions(all);
    }

    /** 工作区和家目录下的 {@code .myagent/agents}、{@code .claude/agents}。 */
    public static AgentDefinitions load(Path workspace) {
        return load(searchPath(workspace, Path.of(System.getProperty("user.home"))));
    }

    /** 优先级从高到低。 */
    static List<Path> searchPath(Path workspace, Path home) {
        List<Path> dirs = new ArrayList<>();
        for (Path base : Stream.of(workspace, home).filter(java.util.Objects::nonNull).toList()) {
            dirs.add(base.resolve(".myagent").resolve("agents"));
            dirs.add(base.resolve(".claude").resolve("agents"));
        }
        return dirs;
    }

    /** {@code dirs} 按优先级从高到低。读的时候倒过来，高的后读、覆盖低的。 */
    static AgentDefinitions load(List<Path> dirs) {
        List<AgentDefinition> found = new ArrayList<>();
        List<Path> lowestFirst = new ArrayList<>(dirs);
        Collections.reverse(lowestFirst);           // List.reversed() 要 Java 21
        for (Path dir : lowestFirst) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".md") && Files.isRegularFile(f))
                        .sorted().toList()) {
                    try {
                        AgentDefinition d = AgentDefinition.parse(file, Files.readString(file));
                        if (d != null) {
                            found.add(d);
                        }
                    } catch (IOException e) {
                        log.warn("读不动 {}，跳过这份 agent 定义: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("列不出 {}: {}", dir, e.getMessage());
            }
        }
        AgentDefinitions loaded = of(found);
        if (loaded.overDescriptionBudget()) {
            log.warn("自定义 agent 类型的描述加起来约 {} token，超过 {} 的预算：它们每一轮都跟着 spawn_agent 的说明发出去。"
                            + "把 description 写短，细节挪进正文（正文只进那个子 agent 自己的系统提示词）",
                    loaded.customDescriptionTokens(), DESCRIPTION_BUDGET_TOKENS);
        }
        return loaded;
    }

    /**
     * 自定义类型的描述加起来的预算，照 Claude Code 的 15,000 token。这些描述<b>每一轮</b>都跟着
     * spawn_agent 的说明发出去，写长了是每一步都要付的税（MCP 工具菜单那笔账算过一次）。
     * 超了只提醒、不丢定义——丢掉哪一份都是替用户做决定。
     */
    public static final int DESCRIPTION_BUDGET_TOKENS = 15_000;

    /** 自定义类型（不含内置的）描述的估算 token 数。 */
    public int customDescriptionTokens() {
        int total = 0;
        for (AgentDefinition d : byName.values()) {
            if (!"builtin".equals(d.source())) {
                total += dev.wiles.myagent.context.TokenEstimator.estimate(d.description());
            }
        }
        return total;
    }

    public boolean overDescriptionBudget() {
        return customDescriptionTokens() > DESCRIPTION_BUDGET_TOKENS;
    }

    public AgentDefinition get(String name) {
        return byName.get(name);
    }

    public Collection<AgentDefinition> all() {
        return byName.values();
    }
}
