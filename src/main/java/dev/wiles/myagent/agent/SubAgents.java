package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.prompt.PromptRepository;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolSearchTool;
import dev.wiles.myagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 主 agent 的子 agent 编排，照 Codex（{@code codex-rs/core/src/tools/handlers/multi_agents}）的 v1 做：
 *
 * <ul>
 *   <li><b>异步</b>：{@code spawn_agent} 立刻返回 id，子 agent 在后台线程上跑，主 agent 接着干自己的。
 *       做完时结果作为 {@code <subagent_notification>} 注入主 agent 的对话（见 {@link Mailbox}），
 *       不用主 agent 去问；真要卡在它的结果上才用 {@code wait_agent}。</li>
 *   <li><b>类型是数据</b>（照 Claude Code 的 subagent 定义）：内置 default / explorer / worker 三份，
 *       用户在 {@code agents/*.md} 里加的同等对待，工具按 tools / disallowedTools 收窄（见 {@link AgentDefinition}）。</li>
 *   <li><b>会写</b>：{@code worker} 直接改工作区——和主 agent、和别的 worker 共用一个目录。
 *       Codex 只靠提示词让各方守住<b>写入集合</b>；这里多做一步，由框架锁住（见 {@link OwnedWrite}）：
 *       派 worker 必须写明 {@code owns}，它跑着的时候别人写不了这些路径，它也只能写这些路径。
 *       cookiecutter 上主 agent 把 prompt.py 派给 agent-5 之后自己也接着改，两边反复互相覆盖——
 *       光靠提示词约束不住。{@code explorer} 没有写文件的工具。</li>
 *   <li><b>能复用</b>：{@code send_input} 给已有的子 agent 追加话（排队或打断），它的上下文还在；
 *       {@code close_agent} 关掉释放名额，{@code resume_agent} 再打开。</li>
 *   <li><b>上限</b>：同时开着的最多 {@link Settings#maxThreads()} 个（做完没关的也算，默认
 *       {@link #DEFAULT_MAX_THREADS}），深度 1——子 agent 的菜单上没有这五个工具（{@link #NEVER_FOR_CHILDREN}）
 *       （Codex 是 {@code agent_max_threads = 6}、{@code agent_max_depth = 1}；并发先压到 3，
 *       cookiecutter 上几个 agent 同时请求就撞 429，再按实验调）。</li>
 *   <li><b>派不派由模型按描述自己定</b>（照 Claude Code 的 Agent 工具）：工具常驻菜单，
 *       {@code spawn_agent} 的说明里写清每种类型什么时候用、什么时候自己做更快。
 *       不在开工前另跑一次决策，也不靠用户开口许可。</li>
 *   <li><b>跟着主对话的权限模式走</b>（照 Claude Code）：主对话在 plan 模式时，
 *       所有子 agent 每一步都只剩只读的工具，不管类型、不管是不是早就派出去了（见 {@link #readOnlyWhen}）。</li>
 * </ul>
 *
 * <h3>两种派法（照 Claude Code）</h3>
 * <ul>
 *   <li><b>按类型派</b>：子 agent 用<b>自己的</b>系统提示词（{@link SystemPrompt#forSubagent}：base + 子 agent 模式 + 类型的规矩），
 *       工具按定义筛，从零开始，只看得到派活时写的那段话。主 agent 那一份里进出 plan 模式、怎么派子 agent 的话它用不上。
 *       同一类型的系统提示词逐字相同，彼此之间前缀缓存照样命中。</li>
 *   <li><b>fork</b>：系统提示词、工具菜单、对话历史都和主对话一模一样，前缀缓存整段命中，所以最便宜。
 *       派遣和进出 plan 模式的工具<b>留在菜单上</b>（换成会拒绝的那一版）——拿掉的话工具定义就和主对话不一样了，缓存全丢。</li>
 * </ul>
 */
public final class SubAgents implements Mailbox {

    private static final Logger log = LoggerFactory.getLogger(SubAgents.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PromptRepository REPO = PromptRepository.createDefault();

    /** 同时开着的子 agent 上限的默认值，做完没关的也算。Codex 默认 6，这里先压到 3。 */
    public static final int DEFAULT_MAX_THREADS = 3;

    /** 子 agent 一轮的墙钟时限默认值：30 分钟，跑不完就叫停、按出错交回。 */
    public static final long DEFAULT_CHILD_TIMEOUT_MILLIS = 30 * 60_000L;

    /**
     * 可调的几个旋钮，都来自环境变量（见 {@link #fromEnv}）。
     *
     * @param maxThreads         同时开着的子 agent 上限
     * @param roles              只允许派这几种类型；空表示能派的都能派（内置的和 {@code agents/*.md} 里的）
     * @param childMaxSteps      子 agent 每一轮的步数上限，{@code <= 0} 不限
     * @param childTimeoutMillis 子 agent 每一轮的墙钟时限，{@code <= 0} 不限
     * @param budget             和主 agent 共用的步数预算，null 表示不共用
     */
    public record Settings(int maxThreads, Set<String> roles, int childMaxSteps, long childTimeoutMillis,
                           StepBudget budget, String model, boolean forceModel) {

        /**
         * 不指定模型：子 agent 跟主对话一样。
         *
         * <p>{@code model} / {@code forceModel} 照 Claude Code 的 {@code CLAUDE_CODE_SUBAGENT_MODEL}
         * 和 {@code ..._FORCE}：前者是排在「调用参数、类型定义」后面的默认值，后者让它压过一切（fork 除外）。
         */
        public Settings(int maxThreads, Set<String> roles, int childMaxSteps, long childTimeoutMillis,
                        StepBudget budget) {
            this(maxThreads, roles, childMaxSteps, childTimeoutMillis, budget, null, false);
        }

        public Settings {
            if (maxThreads <= 0) {
                throw new IllegalArgumentException("子 agent 并发上限要大于 0: " + maxThreads);
            }
            roles = Set.copyOf(roles);
            model = AgentDefinition.normalizeModel(model);
            if (forceModel && model == null) {
                throw new IllegalArgumentException("MYAGENT_SUBAGENT_MODEL_FORCE 要和 MYAGENT_SUBAGENT_MODEL 一起设");
            }
            for (String role : roles) {
                if (!AgentDefinition.NAME.matcher(role).matches()) {
                    throw new IllegalArgumentException("子 agent 类型名只能用小写字母、数字、- 和 _: " + role);
                }
            }
        }

        public static Settings defaults(int childMaxSteps) {
            return new Settings(DEFAULT_MAX_THREADS, Set.of(), childMaxSteps,
                    DEFAULT_CHILD_TIMEOUT_MILLIS, null);
        }

        /**
         * MYAGENT_SUBAGENT_MAX（并发）、MYAGENT_SUBAGENT_ROLES（逗号分隔，如 explorer）、
         * MYAGENT_SUBAGENT_STEPS（每轮步数，默认同主 agent）、MYAGENT_SUBAGENT_TIMEOUT（每轮秒数）、
         * MYAGENT_SUBAGENT_MODEL（子 agent 的默认模型）、MYAGENT_SUBAGENT_MODEL_FORCE=1（所有子 agent 都用它）。
         * 共用预算由调用方传（它也管主 agent）。
         */
        public static Settings fromEnv(java.util.function.Function<String, String> env, int defaultChildSteps,
                                       StepBudget budget) {
            String roles = env.apply("MYAGENT_SUBAGENT_ROLES");
            return new Settings(
                    intOr(env.apply("MYAGENT_SUBAGENT_MAX"), DEFAULT_MAX_THREADS),
                    roles == null || roles.isBlank() ? Set.of()
                            : Set.of(roles.toLowerCase(Locale.ROOT).replace(" ", "").split(",")),
                    intOr(env.apply("MYAGENT_SUBAGENT_STEPS"), defaultChildSteps),
                    intOr(env.apply("MYAGENT_SUBAGENT_TIMEOUT"), (int) (DEFAULT_CHILD_TIMEOUT_MILLIS / 1000)) * 1000L,
                    budget, env.apply("MYAGENT_SUBAGENT_MODEL"),
                    Set.of("1", "true", "yes", "on").contains(String.valueOf(env.apply("MYAGENT_SUBAGENT_MODEL_FORCE"))
                            .strip().toLowerCase(Locale.ROOT)));
        }

        public static int intOr(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("要一个整数，收到: " + value);
            }
        }

        /** 写进评测摘要。 */
        ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode();
            o.put("maxThreads", maxThreads);
            roles.stream().sorted().forEach(o.putArray("roles")::add);
            o.put("childMaxSteps", childMaxSteps);
            o.put("childTimeoutSeconds", childTimeoutMillis / 1000);
            if (budget != null) {
                o.put("totalStepBudget", budget.total());
            }
            if (model != null) {
                o.put("model", model);
                o.put("forceModel", forceModel);
            }
            return o;
        }
    }

    /** 子 agent 超时的看门狗。一个进程一条线程就够：它只负责到点叫停。 */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "subagent-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** 交回主 agent 的一次结果封顶。子 agent 被要求写短，这是兜底。 */
    static final int MAX_RESULT_CHARS = 8_000;

    static final long DEFAULT_WAIT_MILLIS = 30_000;
    static final long MIN_WAIT_MILLIS = 10_000;
    static final long MAX_WAIT_MILLIS = 3_600_000;

    /** 写入方是主 agent 时的名字。 */
    static final String MAIN = "主 agent";

    /** 通知的开头。界面按 user 消息数截转录时要跳过它——那不是人说的话。 */
    public static final String NOTICE_TAG = "<subagent_notification>";

    /** 收尾前的验证提醒（{@link #beforeFinish()}）。同样不是人说的话。 */
    public static final String VERIFY_TAG = "<verify_reminder>";

    /** 通知里列出的命令条数上限：只留最近的，早期的探索命令对判断结果没用。 */
    static final int MAX_EVIDENCE_COMMANDS = 8;

    /**
     * 认作「跑测试」的命令。主 agent 跑过其中之一，子 agent 此前的改动就算验证过了——
     * 不管结果过没过：它看到了真实输出，接受还是返工是它的判断。
     */
    static final Pattern TEST_COMMAND = Pattern.compile(
            "(?<![\\w.-])(pytest|py\\.test|unittest|tox|nox|mvn\\b[^|;&]*\\b(test|verify)|gradlew?\\b[^|;&]*\\btest"
                    + "|npm (run )?test|yarn test|pnpm test|bun test|go test|cargo test|make (test|check)|ctest)(?![\\w.-])");

    /** bash / run_tests 输出末尾的「退出码: N」。 */
    private static final Pattern EXIT_CODE = Pattern.compile("退出码: (-?\\d+)\\s*$");

    /** 开不开。派不派、派几个由模型按工具说明自己定，见类注释。 */
    public enum Mode {
        OFF, ON;

        /** 环境变量 {@code MYAGENT_SUBAGENTS} 的值：on（默认）/ off。 */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return ON;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "off", "0", "false", "no" -> OFF;
                case "on", "1", "true", "yes" -> ON;
                default -> throw new IllegalArgumentException(
                        "MYAGENT_SUBAGENTS 只认 on / off，收到: " + value);
            };
        }
    }

    static {
        // 提示词文件缺了不会有任何症状，只是子 agent 少了规矩。启动就读一遍（内置类型的规矩在 AgentDefinitions 里读）
        for (String path : List.of("subagents/spawn.md", "subagents/fork.md", "modes/subagent.md")) {
            REPO.loadRequired(path);
        }
    }

    /** 进程内全局递增：计划里的子任务也在发带号的事件，各数各的会撞号。 */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();

    private enum State { RUNNING, COMPLETED, ERRORED, INTERRUPTED, SHUTDOWN }

    /** 一个子 agent。字段都在 {@link #lock} 下读写。 */
    private final class Child {
        final String id;
        final AgentDefinition role;
        final Agent agent;
        State state = State.RUNNING;
        /** 关掉之前的状态，resume 时还原。 */
        State beforeClose;
        String result;
        String failure;
        boolean running;
        boolean closed;
        /** 主 agent 被叫停连带停的：不发通知，主 agent 自己知道。 */
        boolean stoppedByParent;
        /** send_input(interrupt=true) 送来的话：这一轮停下后马上拿它开新的一轮。 */
        String interruptWith;
        /** 第几轮。看门狗到点时只叫停它盯的那一轮，不误伤之后新开的。 */
        int turn;
        /** 这一轮是被看门狗叫停的。 */
        boolean timedOut;
        /** send_input 排队的话，它下一步请求前收到（{@link Agent} 的收件箱）。 */
        final List<String> queued = new ArrayList<>();
        /** 它负责的文件/目录（绝对、规范化）。它跑着的时候只有它能写；空表示不独占。 */
        final List<Path> owns;
        /** 它用的模型；null 表示跟主对话一样。 */
        String model;

        // ---- 框架看到的事实（写进通知，不靠它自己说） ----
        /** 写文件工具成功写过的路径。bash 里写的看不到。 */
        final LinkedHashSet<String> filesWritten = new LinkedHashSet<>();
        /** 跑过的 bash / run_tests：命令、退出码、结果摘要。 */
        final List<Evidence> commands = new ArrayList<>();
        /** 被写入锁挡下的次数（越界写）。 */
        int refusals;
        int steps;
        /** 开跑但还没结束的工具调用：结束事件里只有 id，命令要从开始事件里拿。 */
        final Map<Integer, AgentEvent.ToolStarted> inFlight = new ConcurrentHashMap<>();

        Child(String id, AgentDefinition role, Agent agent, List<Path> owns) {
            this.id = id;
            this.role = role;
            this.agent = agent;
            this.owns = owns;
        }
    }

    /** 一条命令的事实：哪个工具、什么命令、退出码（拿不到是 null）、结果摘要。 */
    record Evidence(String tool, String command, Integer exit, String summary) {
    }

    /** 主 agent 收件箱里的一条：哪个子 agent 的、说什么。wait_agent 拿过结果的会被撤掉。 */
    private record Notice(String agentId, String text) {
    }

    private final Object lock = new Object();
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private final AtomicInteger spawned = new AtomicInteger();

    // ---- 收尾验证：子 agent 写过、主 agent 还没跑过测试的文件 ----
    /** 子 agent 写过、主 agent 之后还没跑过测试的文件（显示用的相对路径）。 */
    private final LinkedHashSet<String> unverified = new LinkedHashSet<>();
    /** 提醒过的那一批。同一批不再提醒——主 agent 看了提醒仍决定收尾，那是它的判断。 */
    private final LinkedHashSet<String> reminded = new LinkedHashSet<>();
    private int verifyReminders;
    /** 主 agent 写文件被锁挡下的次数（它去改了派出去的文件）。 */
    private int mainRefusals;

    private final Supplier<LlmClient> clients;
    private final ToolRegistry allTools;
    private final Path root;
    private final Supplier<ContextManager> contexts;
    private final Settings settings;
    private final BiConsumer<String, AgentEvent> events;
    private volatile Agent parent;
    /** 主对话眼下是不是 plan 模式。是的话子 agent 都只剩只读的工具，见 {@link #childMayUse}。 */
    private volatile BooleanSupplier readOnly = () -> false;
    /** 会话是只读启动的（{@code --readonly}）：按类型派的子 agent 的系统提示词里也要明说。 */
    private volatile boolean readOnlyPolicy;
    /** 按模型名造客户端（{@link #withModelClients}）。null 表示这个会话不能换模型，子 agent 都用默认的。 */
    private volatile java.util.function.Function<String, LlmClient> modelClients;
    /** 能派哪些类型。默认只有内置的，{@link #withDefinitions} 换成带自定义的。 */
    private volatile AgentDefinitions definitions = AgentDefinitions.builtins();

    /**
     * @param clients       <b>每个子 agent 现造一个</b>客户端：{@code DeepSeekClient} 带可变状态，共用会互相踩取消
     * @param allTools      主 agent 的全量注册表，子 agent 的菜单在派出那一刻从这里筛
     * @param workspaceRoot 工作区根目录，{@code owns} 和写文件的路径都按它解析
     * @param contexts      每个子 agent 一份上下文管理器（和主 agent 同样的窗口）
     * @param settings      并发、允许的类型、子 agent 的步数和时限、共用预算
     * @param events        子 agent 的动静往哪发，第一个参数是它的 id（{@code agent-N}）；传 null 不发
     */
    public SubAgents(Supplier<LlmClient> clients, ToolRegistry allTools, Path workspaceRoot,
                     Supplier<ContextManager> contexts, Settings settings, Mode mode,
                     BiConsumer<String, AgentEvent> events) {
        if (mode == Mode.OFF) {
            throw new IllegalArgumentException("OFF 就别造它");
        }
        this.clients = clients;
        this.allTools = allTools;
        this.root = workspaceRoot.toAbsolutePath().normalize();
        this.contexts = contexts;
        this.settings = settings;
        this.events = events == null ? (id, e) -> { } : events;
    }

    /**
     * 主对话眼下是不是 plan 模式。plan 模式由界面随时切换，所以给的是一个现查的开关。
     *
     * <p>照 Claude Code：子 agent <b>跟着主对话的权限模式走</b>，而不是按类型挡——plan 模式下不管派的是什么类型、
     * 是不是早就派出去在跑，每一步的菜单都只剩只读的工具（{@link #childMayUse}）。
     * 原来的做法是「plan 模式只许派 explorer」，可 explorer 手上有 bash，照样能写文件；
     * 而一个 plan 模式之前派出去的 worker，切进 plan 模式之后还在接着改。
     */
    public SubAgents readOnlyWhen(BooleanSupplier readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * 能按模型名造客户端时接上（同一个服务、同一把 key，换个模型）。接了之后子 agent 才能换模型，
     * 见 {@link #modelFor}；不接就都用构造时给的默认客户端。
     */
    public SubAgents withModelClients(java.util.function.Function<String, LlmClient> modelClients) {
        this.modelClients = modelClients;
        return this;
    }

    /**
     * 这个子 agent 用哪个模型，null 表示跟主对话一样。照 Claude Code 的顺序：
     * 派的时候给的 {@code model} → 类型定义里的 {@code model} → {@code MYAGENT_SUBAGENT_MODEL}；
     * 设了 {@code MYAGENT_SUBAGENT_MODEL_FORCE} 就一律用后者。fork 永远跟主对话一样——它要和主对话共用前缀缓存。
     */
    String modelFor(AgentDefinition role, String requested) {
        if (role == AgentDefinitions.FORK) {
            return null;
        }
        if (settings.forceModel()) {
            return settings.model();
        }
        if (requested != null) {
            return requested;
        }
        return role.model() != null ? role.model() : settings.model();
    }

    /** 会话是只读启动的：子 agent 的系统提示词跟主 agent 一样带上只读那段。 */
    public SubAgents withReadOnlyPolicy(boolean readOnly) {
        this.readOnlyPolicy = readOnly;
        return this;
    }

    /**
     * fork 这一步的菜单：和主 agent 用同一条规矩（{@link PlanMode#allows} 的语义），菜单才逐字一样、缓存才命中。
     * 派遣、进出 plan 模式那几个在菜单上，但调了会被拒绝（见 {@link #forkTools}）。
     */
    boolean forkMayUse(String tool) {
        return readOnly.getAsBoolean() ? PlanMode.READ_ONLY_TOOLS.contains(tool) : !PlanMode.EXIT_TOOL.equals(tool);
    }

    /** 子 agent 这一步能不能用这个工具：主对话在 plan 模式时，只放只读的。 */
    boolean childMayUse(String tool) {
        return !readOnly.getAsBoolean() || PlanMode.READ_ONLY_TOOLS.contains(tool);
    }

    /**
     * 换一套可派的类型（内置的加上 {@code agents/*.md} 里的，见 {@link AgentDefinitions#load}）。
     * 白名单里对不上任何工具的条目这里就说出来：写错一个名字的症状是「它就是不用那个工具」，
     * 和那个工具不存在长得一模一样。
     */
    public SubAgents withDefinitions(AgentDefinitions definitions) {
        this.definitions = definitions;
        List<String> available = inheritable();
        for (AgentDefinition d : definitions.all()) {
            List<String> unmatched = d.unmatched(available);
            if (!unmatched.isEmpty()) {
                log.warn("agent 类型 {}（{}）的 tools 里这些对不上任何工具: {}", d.name(), d.source(), unmatched);
            }
            if (d.resolve(available).isEmpty()) {
                log.warn("agent 类型 {}（{}）算下来一个工具都没有", d.name(), d.source());
            }
        }
        for (String name : settings.roles()) {
            if (definitions.get(name) == null) {
                log.warn("MYAGENT_SUBAGENT_ROLES 里的 {} 不是已知的 agent 类型，忽略", name);
            }
        }
        return this;
    }

    /**
     * 接到主 agent 上：它的系统提示词子 agent 沿用，它的收件箱就是这里。
     * 同时把注册表里的写文件工具换成带归属检查的那一版（写入方记作主 agent）。
     */
    public SubAgents attach(Agent parent) {
        this.parent = parent;
        parent.withMailbox(this);
        for (Tool tool : List.copyOf(allTools.all())) {
            if (BuiltinTools.WRITING_TOOLS.contains(tool.name()) && !(tool instanceof OwnedWrite)) {
                allTools.replace(new OwnedWrite(tool, MAIN));
            } else if (Set.of("bash", "run_tests").contains(tool.name()) && !(tool instanceof MainTestWatch)) {
                allTools.replace(new MainTestWatch(tool));
            }
        }
        return this;
    }

    /**
     * 收尾检查：子 agent 写过的文件，主 agent 之后有没有跑过测试。
     * 没有就提醒一次（同一批文件只提醒一次），列出是哪些文件、谁改的。
     */
    @Override
    public String beforeFinish() {
        synchronized (lock) {
            if (unverified.isEmpty() || reminded.containsAll(unverified)) {
                return null;
            }
            reminded.addAll(unverified);
            verifyReminders++;
            return VERIFY_TAG + "\n子 agent 改过这些文件，你之后还没跑过测试：" + String.join("、", unverified)
                    + "。\n子 agent 说做完了不算数。收尾前先看一眼这些改动、跑一遍相关测试，"
                    + "根据真实结果决定接受、修补还是返工；确实无法验证就在答复里明说没验证。\n</verify_reminder>";
        }
    }

    /**
     * 评测摘要用：派了几个、写入冲突几次、收尾提醒几次，以及每个子 agent 的事实记录。
     */
    public ObjectNode report() {
        synchronized (lock) {
            ObjectNode out = JSON.createObjectNode();
            out.put("spawned", spawned.get());
            ObjectNode shown = settings.toJson();
            var shownRoles = shown.putArray("roles");                // 换成实际能派的，而不是「空 = 全部」
            allowed().forEach(d -> shownRoles.add(d.name()));
            out.set("settings", shown);
            if (settings.budget() != null) {
                out.put("totalStepsUsed", settings.budget().used());
            }
            int childRefusals = children.values().stream().mapToInt(c -> c.refusals).sum();
            out.put("writeConflicts", mainRefusals + childRefusals);
            out.put("mainWriteRefusals", mainRefusals);
            out.put("verifyReminders", verifyReminders);
            out.put("unverifiedAtEnd", unverified.size());
            var list = out.putArray("children");
            for (Child c : children.values()) {
                ObjectNode n = list.addObject();
                n.put("id", c.id);
                n.put("type", c.role.name());
                if (c.model != null) {
                    n.put("model", c.model);
                }
                n.put("state", c.state.name().toLowerCase(Locale.ROOT));
                n.put("steps", c.steps);
                c.owns.forEach(o -> n.withArray("owns").add(show(o)));
                c.filesWritten.forEach(n.putArray("filesWritten")::add);
                n.put("commands", c.commands.size());
                n.put("testRuns", c.commands.stream().filter(e -> TEST_COMMAND.matcher(e.command()).find()).count());
                n.put("refusals", c.refusals);
            }
            return out;
        }
    }

    /**
     * 叫停所有在跑的子 agent，最多等 {@code graceMillis} 让它们停稳。
     * 无头运行退出前调：不然进程一退，子 agent 写到一半的文件就留在那儿。
     *
     * @return 都停下了
     */
    public boolean stopAll(long graceMillis) {
        interruptAll();
        long until = System.nanoTime() + graceMillis * 1_000_000L;
        synchronized (lock) {
            while (children.values().stream().anyMatch(c -> c.running)) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** 五个工具。注册到主 agent 的注册表里。 */
    public List<Tool> tools() {
        return List.of(new Spawn(), new SendInput(), new Wait(), new Close(), new Resume());
    }

    /** 一共派出过几个。评测摘要用。 */
    public int spawned() {
        return spawned.get();
    }

    public static boolean isNotice(String content) {
        return content != null && (content.startsWith(NOTICE_TAG) || content.startsWith(VERIFY_TAG));
    }

    /**
     * 往主 agent 的收件箱里放一段话，它下一次请求模型前收到。
     * 开局委派计划走这条：排在用户原话之后、第一次请求之前，用户原话仍然是记录里的第一条。
     */
    public void post(String text) {
        synchronized (lock) {
            notices.add(new Notice("", text));
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- 主 agent 的收件箱

    @Override
    public List<String> drain() {
        synchronized (lock) {
            List<String> out = notices.stream().map(Notice::text).toList();
            notices.clear();
            return out;
        }
    }

    @Override
    public boolean hasMail() {
        synchronized (lock) {
            return !notices.isEmpty();
        }
    }

    @Override
    public boolean busy() {
        synchronized (lock) {
            return children.values().stream().anyMatch(c -> c.running && !c.closed);
        }
    }

    @Override
    public void awaitChange(long millis) throws InterruptedException {
        synchronized (lock) {
            lock.wait(Math.max(1, millis));
        }
    }

    @Override
    public void interruptAll() {
        synchronized (lock) {
            for (Child child : children.values()) {
                if (child.running) {
                    child.stoppedByParent = true;
                    child.interruptWith = null;
                    child.agent.cancel();
                }
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            for (Child child : children.values()) {
                child.closed = true;
                if (child.running) {
                    child.agent.cancel();
                }
            }
            children.clear();
            notices.clear();
            unverified.clear();
            reminded.clear();
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 开一轮。调用方持有 {@link #lock}。 */
    private void startTurn(Child child, String input) {
        child.running = true;
        child.state = State.RUNNING;
        child.failure = null;
        child.stoppedByParent = false;
        child.timedOut = false;
        int turn = ++child.turn;
        if (settings.childTimeoutMillis() > 0) {
            WATCHDOG.schedule(() -> {
                synchronized (lock) {
                    if (child.running && child.turn == turn) {
                        child.timedOut = true;
                        child.agent.cancel();
                        log.warn("子 agent {} 超过 {} 秒，已叫停", child.id, settings.childTimeoutMillis() / 1000);
                    }
                }
            }, settings.childTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        Thread worker = new Thread(() -> {
            String text = null;
            Throwable error = null;
            try {
                text = child.agent.run(input);
            } catch (Throwable t) {
                error = t;
                log.error("子 agent {} 炸了", child.id, t);
            }
            finished(child, text, error);
        }, child.id);
        worker.setDaemon(true);
        worker.start();
    }

    /** 一轮跑完（正常收口、出错、被叫停都走这儿），在子 agent 自己的线程上。 */
    private void finished(Child child, String text, Throwable error) {
        synchronized (lock) {
            child.running = false;
            try {
                if (child.closed) {
                    child.state = State.SHUTDOWN;
                    return;
                }
                if (child.interruptWith != null) {
                    String message = child.interruptWith;
                    child.interruptWith = null;
                    child.state = State.INTERRUPTED;
                    startTurn(child, agentMessage(message));
                    return;
                }
                if (child.stoppedByParent) {
                    child.state = State.INTERRUPTED;
                    return;
                }
                if (child.timedOut) {
                    // 超时不是「做完了」：按出错交回，带上它到此为止的话，主 agent 决定返工还是接手
                    child.state = State.ERRORED;
                    child.result = "超过 " + settings.childTimeoutMillis() / 1000 + " 秒的时限，被叫停"
                            + (text == null || text.isBlank() ? "" : "。它到此为止的产出（可能不完整）:\n" + text);
                    notices.add(new Notice(child.id, notice(child)));
                    return;
                }
                if (error != null) {
                    child.state = State.ERRORED;
                    child.result = "子 agent 执行失败: " + error;
                } else if (child.failure != null) {
                    child.state = State.ERRORED;
                    child.result = child.failure + (text == null || text.isBlank() ? ""
                            : "。它到此为止的产出（可能不完整）:\n" + text);
                } else {
                    child.state = State.COMPLETED;
                    child.result = text == null ? "" : text;
                }
                if (!child.queued.isEmpty()) {
                    // 收口那一刻又有话排进来了：接着跑一轮，结果等这一轮完了再交
                    List<String> more = new ArrayList<>(child.queued);
                    child.queued.clear();
                    startTurn(child, String.join("\n\n", more.stream().map(SubAgents::agentMessage).toList()));
                    return;
                }
                notices.add(new Notice(child.id, notice(child)));
                log.info("子 agent {} 结束: {}", child.id, child.state);
            } finally {
                lock.notifyAll();
            }
        }
    }

    /** 开着的（没关的）有几个。调用方持有 {@link #lock}。 */
    private long open() {
        return children.values().stream().filter(c -> !c.closed).count();
    }

    // ---------------------------------------------------------------- 状态的 JSON 形状（照 Codex）

    /** "running" / {"completed": "..."} / {"errored": "..."} / "interrupted" / "shutdown" / "not_found"。 */
    private static JsonNode status(Child child) {
        if (child == null) {
            return JSON.getNodeFactory().textNode("not_found");
        }
        return switch (child.state) {
            case RUNNING -> JSON.getNodeFactory().textNode("running");
            case INTERRUPTED -> JSON.getNodeFactory().textNode("interrupted");
            case SHUTDOWN -> JSON.getNodeFactory().textNode("shutdown");
            case COMPLETED -> JSON.createObjectNode().put("completed", clip(child.result));
            case ERRORED -> JSON.createObjectNode().put("errored", clip(child.result));
        };
    }

    private static boolean isFinal(Child child) {
        return child == null || !child.running;
    }

    private static String notice(Child child) {
        ObjectNode body = JSON.createObjectNode();
        body.put("agent_id", child.id);
        body.put("agent_type", child.role.name());
        body.set("status", status(child));
        body.set("observed", observed(child));
        return NOTICE_TAG + "\n" + body.toPrettyString() + "\n</subagent_notification>";
    }

    /**
     * 框架记录的事实：它实际写了哪些文件、跑了哪些命令、结果如何、越界写被挡了几次。
     * 和它自己的总结放在一起，主 agent 一眼能看出「说测试全过」有没有真跑过。
     */
    private static ObjectNode observed(Child child) {
        ObjectNode o = JSON.createObjectNode();
        child.filesWritten.forEach(o.putArray("files_written")::add);
        var cmds = o.putArray("commands");
        List<Evidence> all = child.commands;
        for (Evidence e : all.subList(Math.max(0, all.size() - MAX_EVIDENCE_COMMANDS), all.size())) {
            ObjectNode n = cmds.addObject();
            n.put("tool", e.tool());
            n.put("command", e.command());
            if (e.exit() == null) {
                n.putNull("exit");
            } else {
                n.put("exit", e.exit());
            }
            n.put("summary", e.summary());
        }
        if (all.size() > MAX_EVIDENCE_COMMANDS) {
            o.put("commands_omitted", all.size() - MAX_EVIDENCE_COMMANDS);
        }
        o.put("tests_run", all.stream().anyMatch(e -> TEST_COMMAND.matcher(e.command()).find()));
        o.put("write_refusals", child.refusals);
        o.put("steps", child.steps);
        o.put("note", "以上是框架记录的实际工具调用，不是它的自述；用 bash 写的文件不在 files_written 里");
        return o;
    }

    /** 从开始/结束事件里记下事实。在子 agent 的线程上调。 */
    private void record(Child child, AgentEvent event) {
        if (event instanceof AgentEvent.Thinking) {
            synchronized (lock) {
                child.steps++;
            }
        } else if (event instanceof AgentEvent.ToolStarted t) {
            child.inFlight.put(t.id(), t);
        } else if (event instanceof AgentEvent.ToolFinished f) {
            AgentEvent.ToolStarted t = child.inFlight.remove(f.id());
            if (t == null || !f.ok()) {
                return;             // 被拒的写入在 checkWrite 里记过了；工具自己失败的不算事实
            }
            JsonNode args = parseArgs(t.arguments());
            synchronized (lock) {
                if (BuiltinTools.WRITING_TOOLS.contains(f.tool())) {
                    for (String path : writePaths(args)) {
                        String shown = show(resolve(path));
                        child.filesWritten.add(shown);
                        unverified.add(shown);
                        reminded.remove(shown);       // 又改了一遍，是新的一批
                    }
                } else if (Set.of("bash", "run_tests").contains(f.tool())) {
                    child.commands.add(evidence(f.tool(), args.path("command").asText(""), f.output()));
                }
            }
        }
    }

    static Evidence evidence(String tool, String command, String output) {
        String out = output == null ? "" : output.strip();
        Integer exit = null;
        Matcher m = EXIT_CODE.matcher(out);
        if (m.find()) {
            exit = Integer.parseInt(m.group(1));
            out = out.substring(0, m.start()).strip();
        }
        String[] lines = out.split("\n");
        String last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        String cmd = command.replaceAll("\\s+", " ").strip();
        return new Evidence(tool, cmd.length() > 200 ? cmd.substring(0, 200) + "…" : cmd, exit,
                last.length() > 200 ? last.substring(0, 200) + "…" : last);
    }

    private static JsonNode parseArgs(String json) {
        try {
            JsonNode n = json == null ? null : JSON.readTree(json);
            return n == null ? JSON.createObjectNode() : n;
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /** 写文件工具参数里的路径：顶层 path、files[].path、dirs[]（和 {@link OwnedWrite} 同一个口径）。 */
    private static List<String> writePaths(JsonNode args) {
        List<String> paths = new ArrayList<>();
        if (args.path("path").isTextual()) {
            paths.add(args.path("path").asText());
        }
        args.path("files").forEach(f -> {
            if (f.path("path").isTextual()) {
                paths.add(f.path("path").asText());
            }
        });
        return paths;
    }

    private static String agentMessage(String message) {
        return "<agent_message>\n" + message + "\n</agent_message>";
    }

    static String clip(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() <= MAX_RESULT_CHARS) {
            return t;
        }
        return t.substring(0, MAX_RESULT_CHARS)
                + "\n…（共 " + t.length() + " 字符，后面截掉了；需要细节就自己去读对应文件）";
    }

    private static ToolResult json(JsonNode node) {
        return ToolResult.ok(node.toPrettyString());
    }

    // ---------------------------------------------------------------- 子 agent 的构造

    /**
     * 子 agent 一律拿不到的：派子 agent 的五个（深度 1），和进出 plan 模式的两个——
     * plan 模式是主对话的事，子 agent 只跟着它（照 Claude Code：EnterPlanMode / ExitPlanMode 不给子 agent）。
     */
    static final Set<String> NEVER_FOR_CHILDREN = Set.of(
            "spawn_agent", "send_input", "wait_agent", "close_agent", "resume_agent",
            PlanMode.ENTER_TOOL, PlanMode.EXIT_TOOL);

    /** 主 agent 给得出、子 agent 有可能拿到的工具名，按注册顺序。 */
    private List<String> inheritable() {
        return allTools.all().stream().map(Tool::name).filter(n -> !NEVER_FOR_CHILDREN.contains(n)).toList();
    }

    /**
     * 按定义从全量注册表里筛（见 {@link AgentDefinition#resolve}）。
     * 写文件的工具换成以这个子 agent 为写入方的那一版。
     */
    private ToolRegistry toolsFor(AgentDefinition role, String id) {
        Set<String> menu = role.resolve(inheritable());
        ToolRegistry narrowed = new ToolRegistry();
        for (Tool tool : allTools.all()) {
            if (!menu.contains(tool.name())) {
                continue;
            }
            narrowed.register(tool instanceof OwnedWrite w ? new OwnedWrite(w.inner, id)
                    : tool instanceof MainTestWatch m ? m.inner : tool);
        }
        // 延迟加载照搬；tool_search 换成搜它自己这张表的，免得拉到它菜单上没有的工具
        narrowed.defer(allTools.deferredTools().stream().map(Tool::name).toList());
        if (narrowed.get(ToolSearchTool.NAME) != null) {
            narrowed.replace(new ToolSearchTool(narrowed));
        }
        return narrowed;
    }

    /**
     * fork 的工具：主 agent 的整张表，顺序、名字、说明、参数一字不差。写文件的换成以它为写入方的那一版；
     * {@link #NEVER_FOR_CHILDREN} 里的换成同一副外表、调了就拒绝的那一版（fork 不能再派，plan 模式归主对话管）。
     */
    private ToolRegistry forkTools(String id) {
        ToolRegistry same = new ToolRegistry();
        for (Tool tool : allTools.all()) {
            same.register(NEVER_FOR_CHILDREN.contains(tool.name()) ? new Refused(tool)
                    : tool instanceof OwnedWrite w ? new OwnedWrite(w.inner, id)
                    : tool instanceof MainTestWatch m ? m.inner : tool);
        }
        same.defer(allTools.deferredTools().stream().map(Tool::name).toList());
        if (same.get(ToolSearchTool.NAME) != null) {
            same.replace(new ToolSearchTool(same));
        }
        return same;
    }

    /** 外表和原工具一样、调了就拒绝。 */
    private record Refused(Tool inner) implements Tool {
        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public String parametersSchema() {
            return inner.parametersSchema();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return ToolResult.error(inner.name() + " 不能用：你是 fork 出来的分身，不能再派子 agent，也不能进出 plan 模式。"
                    + "需要的话写进最后的答复，交给主 agent");
        }
    }

    /** 相对工作区的路径解析成绝对、规范化的。 */
    private Path resolve(String path) {
        Path p = Path.of(path.strip());
        return (p.isAbsolute() ? p : root.resolve(p)).normalize();
    }

    /** 给人看的路径：工作区里的写成相对的。 */
    private String show(Path p) {
        return p.startsWith(root) ? String.valueOf(root.relativize(p)) : p.toString();
    }

    /**
     * 这次写入能不能做。null 表示可以，否则是拒绝的理由。
     *
     * <ul>
     *   <li>路径归一个<b>正在跑</b>的子 agent，写入方又不是它——拒绝</li>
     *   <li>写入方自己有 owns，路径不在里面——拒绝（worker 只写自己负责的）</li>
     * </ul>
     * 子 agent 交了结果（不在跑了），它那片就放开，主 agent 可以接着整合。
     */
    String checkWrite(String writer, List<String> paths) {
        synchronized (lock) {
            Child self = children.get(writer);
            for (String raw : paths) {
                Path p = resolve(raw);
                for (Child c : children.values()) {
                    if (c.id.equals(writer) || !c.running || c.closed) {
                        continue;
                    }
                    for (Path o : c.owns) {
                        if (p.startsWith(o)) {
                            if (self != null) {
                                self.refusals++;
                            } else {
                                mainRefusals++;
                            }
                            return show(p) + " 现在归 " + c.id + "（正在跑，负责 " + owned(c) + "）。"
                                    + "等它交回结果再改，或者用 send_input 让它改，或者 close_agent 收回。"
                                    + "派出去的活不要自己再做一遍。";
                        }
                    }
                }
                if (self != null && !self.owns.isEmpty() && self.owns.stream().noneMatch(p::startsWith)) {
                    self.refusals++;
                    return "你只负责 " + owned(self) + "，" + show(p) + " 不归你。"
                            + "需要改别的文件，就在最后的答复里写清楚要改什么，交给主 agent。";
                }
            }
            return null;
        }
    }

    private String owned(Child c) {
        return String.join("、", c.owns.stream().map(this::show).toList());
    }

    /**
     * fork：主 agent 的对话截到发起这次派遣的那条 assistant 之前。
     * 那条消息里的 tool_calls 此刻还没有结果，带过去就是一条无主的调用，请求直接 400。
     */
    private static List<ChatMessage> forked(List<ChatMessage> conversation) {
        int cut = conversation.size();
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ChatMessage m = conversation.get(i);
            if ("assistant".equals(m.role()) && m.hasToolCalls()) {
                cut = i;
                break;
            }
        }
        return conversation.subList(0, cut);
    }

    /** fork 收到的第一条：交代它是分身、只做这一件事，再接上任务。别的类型的规矩在它们自己的系统提示词里。 */
    String forkBrief(String id, String message) {
        return REPO.loadRequired("subagents/fork.md")
                .replace("{{id}}", id)
                .replace("{{message}}", message);
    }

    /** 这次能派的类型：定义里有的，再按 {@link Settings#roles()} 收窄（空表示不收窄）。 */
    List<AgentDefinition> allowed() {
        return definitions.all().stream()
                .filter(d -> settings.roles().isEmpty() || settings.roles().contains(d.name())).toList();
    }

    String spawnDescription() {
        StringBuilder roles = new StringBuilder();
        for (AgentDefinition role : allowed()) {
            roles.append("- ").append(role.name()).append("：").append(role.description());
            if (role.tools() != null) {
                // 白名单收窄过的，把菜单亮出来：派之前就知道它干不干得了
                roles.append("（工具：").append(String.join(", ", role.resolve(inheritable()))).append('）');
            }
            if (role.ownsRequired() && role != AgentDefinitions.WORKER) {
                roles.append("（派它必须写 owns）");
            }
            if (role.model() != null) {
                roles.append("（模型：").append(role.model()).append('）');
            }
            roles.append('\n');
        }
        return REPO.loadRequired("subagents/spawn.md")
                .replace("{{roles}}", roles.toString().strip())
                .strip();
    }

    // ---------------------------------------------------------------- 五个工具

    private final class Spawn implements Tool {
        @Override
        public String name() {
            return "spawn_agent";
        }

        @Override
        public String description() {
            return spawnDescription();
        }

        @Override
        public String parametersSchema() {
            String types = String.join(", ", allowed().stream().map(d -> "\"" + d.name() + "\"").toList());
            return """
                    {
                      "type": "object",
                      "properties": {
                        "message": {"type": "string", "description": "交给新 agent 的任务。它默认看不到你的对话，要把它需要知道的都写进来"},
                        "agent_type": {"type": "string", "enum": [%s], "description": "不传就是 default"},
                        "model": {"type": "string", "description": "这次用的模型，不传就按类型定义、再按会话默认（跟你一样）。简单、量大的活（查文件、跑测试、照着样子改）可以换便宜的模型；fork 不能换"},
                        "owns": {"type": "array", "items": {"type": "string"}, "description": "它负责（独占写）的文件或目录，相对工作区。worker 必填。它跑着的时候你和别的 agent 写不了这些路径，它也只能写这些路径；它交回结果后放开"}
                      },
                      "required": ["message"]
                    }
                    """.formatted(types);
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String message = Args.requireString(args, "message").strip();
            String type = args.path("agent_type").asText("default").strip().toLowerCase(Locale.ROOT);
            AgentDefinition role = definitions.get(type.isEmpty() ? "default" : type);
            if (role == null) {
                return ToolResult.error("agent_type 只认 "
                        + String.join(" / ", definitions.all().stream().map(AgentDefinition::name).toList())
                        + "，收到: " + type);
            }
            // plan 模式不在这儿挡：子 agent 跟着主对话的权限模式走（见 toolGate），派出去也只拿得到只读的工具
            if (!allowed().contains(role)) {
                return ToolResult.error("这次只允许派 "
                        + String.join(" / ", allowed().stream().map(AgentDefinition::name).toList())
                        + "，不能派 " + role.name() + "。这部分你自己做。");
            }
            Agent parentAgent = parent;
            if (parentAgent == null) {
                return ToolResult.error("子 agent 没接到主 agent 上（SubAgents.attach 没调）");
            }
            List<Path> owns = Args.optStringList(args, "owns").stream()
                    .filter(o -> !o.isBlank()).map(SubAgents.this::resolve).distinct().toList();
            if (role.ownsRequired() && owns.isEmpty()) {
                return ToolResult.error("派 " + role.name() + " 要用 owns 写明它负责哪些文件或目录——"
                        + "它跑着的时候只有它能写这些路径，别人（包括你）写不了，这样才不会互相覆盖");
            }
            for (Path o : owns) {
                if (!o.startsWith(root) || o.equals(root)) {
                    return ToolResult.error("owns 要是工作区里的具体文件或目录，不能是整个工作区: " + o);
                }
            }
            synchronized (lock) {
                if (open() >= settings.maxThreads()) {
                    return ToolResult.error("已经开着 " + settings.maxThreads() + " 个子 agent 了（做完没关的也占名额）。"
                            + "先用 close_agent 关掉不再需要的，或者自己做。");
                }
            }
            synchronized (lock) {
                for (Child c : children.values()) {
                    if (!c.running || c.closed) {
                        continue;
                    }
                    for (Path o : owns) {
                        for (Path theirs : c.owns) {
                            if (o.startsWith(theirs) || theirs.startsWith(o)) {
                                return ToolResult.error(show(o) + " 和正在跑的 " + c.id + " 负责的 " + show(theirs)
                                        + " 重叠了。写入范围要互不相交：换一种拆法，或者等它做完");
                            }
                        }
                    }
                }
            }
            String requested;
            try {
                requested = AgentDefinition.normalizeModel(args.path("model").asText(null));
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            if (requested != null && role == AgentDefinitions.FORK) {
                return ToolResult.error("fork 不能换模型：它和你共用前缀缓存，换了模型缓存就全丢了。要换模型就派别的类型");
            }
            String model = modelFor(role, requested);
            java.util.function.Function<String, LlmClient> byModel = modelClients;
            if (model != null && byModel == null) {
                log.warn("这个会话不能给子 agent 换模型，{} 照旧用默认模型", model);
                model = null;
            }
            String id = "agent-" + SEQ.incrementAndGet();
            boolean fork = role == AgentDefinitions.FORK;
            // fork：系统提示词、工具、历史都和主对话一样，前缀缓存整段命中；
            // 别的类型：自己的系统提示词、筛过的工具、从零开始（照 Claude Code）
            Agent agent = new Agent(model == null ? clients.get() : byModel.apply(model),
                    fork ? forkTools(id) : toolsFor(role, id),
                    fork ? parentAgent.systemPrompt() : SystemPrompt.forSubagent(root, role, readOnlyPolicy),
                    settings.childMaxSteps(), contexts.get())
                    .withWritingTools(BuiltinTools.WRITING_TOOLS)
                    .withStepBudget(settings.budget())
                    .withToolGate(fork ? SubAgents.this::forkMayUse : SubAgents.this::childMayUse);
            if (fork) {
                agent.restore(forked(parentAgent.conversation()));
            }
            Child child = new Child(id, role, agent, owns);
            child.model = model;
            agent.withMailbox(new ChildInbox(child));
            agent.onEvent(event -> {
                if (event instanceof AgentEvent.Failed f) {
                    synchronized (lock) {
                        child.failure = f.reason();
                    }
                }
                record(child, event);
                events.accept(id, event);
            });
            synchronized (lock) {
                if (open() >= settings.maxThreads()) {      // 两次检查之间别人也派了
                    return ToolResult.error("已经开着 " + settings.maxThreads() + " 个子 agent 了，先 close_agent 关掉不需要的");
                }
                children.put(id, child);
                String task = owns.isEmpty() ? message
                        : message + "\n\n你负责（只有你能写、你也只能写）：" + owned(child);
                startTurn(child, fork ? forkBrief(id, task) : task);
            }
            spawned.incrementAndGet();
            log.info("派出 {}（{}）: {}", id, role.name(), message.length() > 80 ? message.substring(0, 80) + "…" : message);
            ObjectNode out = JSON.createObjectNode();
            out.put("agent_id", id);
            out.putNull("nickname");
            return json(out);
        }
    }

    private final class SendInput implements Tool {
        @Override
        public String name() {
            return "send_input";
        }

        @Override
        public String description() {
            return "给一个已有的子 agent 发消息。interrupt=true 立刻打断它手上的事、改做这个；"
                    + "不传或 false 就排队，它在下一步看到（它已经做完的话，就以这条消息开始新的一轮）。"
                    + "要派的活和某个子 agent 之前做的高度相关时，用 send_input 复用它——它的上下文还在——而不是新派一个。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "target": {"type": "string", "description": "子 agent 的 id（spawn_agent 返回的）"},
                        "message": {"type": "string", "description": "要发给它的话"},
                        "interrupt": {"type": "boolean", "description": "true 打断它当前的事立刻处理这条；不传或 false 排队"}
                      },
                      "required": ["target", "message"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String target = Args.requireString(args, "target").strip();
            String message = Args.requireString(args, "message").strip();
            boolean interrupt = args.path("interrupt").asBoolean(false);
            synchronized (lock) {
                Child child = children.get(target);
                if (child == null) {
                    return ToolResult.error("没有这个子 agent: " + target);
                }
                if (child.closed) {
                    return ToolResult.error(target + " 已经关掉了，先 resume_agent 再发");
                }
                if (!child.running) {
                    notices.removeIf(n -> n.agentId().equals(target));   // 上一轮的结果它已经不需要了
                    startTurn(child, agentMessage(message));
                } else if (interrupt) {
                    child.interruptWith = message;
                    child.agent.cancel();
                } else {
                    child.queued.add(message);
                }
            }
            return json(JSON.createObjectNode().put("submission_id", "sub-" + SUBMISSIONS.incrementAndGet()));
        }
    }

    private final class Wait implements Tool {
        @Override
        public String name() {
            return "wait_agent";
        }

        @Override
        public String description() {
            return "等子 agent 进入最终状态。传多个 id 时，哪个先结束就返回。completed 里带着它的最终答复。"
                    + "超时返回空的 status。子 agent 结束时，同样的结果也会以通知的形式自动送到你这里——"
                    + "所以只在关键路径的下一步马上要用它的结果、不拿到就做不下去时才等。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "targets": {"type": "array", "items": {"type": "string"}, "description": "要等的子 agent id；传多个时任一个结束就返回"},
                        "timeout_ms": {"type": "number", "description": "超时毫秒数。默认 %d，最小 %d，最大 %d。宁可等久一点（几分钟），别反复轮询"}
                      },
                      "required": ["targets"]
                    }
                    """.formatted(DEFAULT_WAIT_MILLIS, MIN_WAIT_MILLIS, MAX_WAIT_MILLIS);
        }

        @Override
        public ToolResult execute(JsonNode args) {
            List<String> targets = Args.optStringList(args, "targets");
            if (targets.isEmpty()) {
                return ToolResult.error("targets 不能为空");
            }
            long timeout = args.has("timeout_ms") ? args.get("timeout_ms").asLong(DEFAULT_WAIT_MILLIS)
                    : DEFAULT_WAIT_MILLIS;
            if (timeout <= 0) {
                return ToolResult.error("timeout_ms 必须大于 0");
            }
            timeout = Math.max(MIN_WAIT_MILLIS, Math.min(MAX_WAIT_MILLIS, timeout));
            long deadline = System.nanoTime() + timeout * 1_000_000L;
            synchronized (lock) {
                while (true) {
                    ObjectNode statuses = JSON.createObjectNode();
                    for (String id : targets) {
                        Child child = children.get(id);
                        if (isFinal(child)) {
                            statuses.set(id, status(child));
                        }
                    }
                    if (!statuses.isEmpty()) {
                        // 结果在这儿拿到了，同一份通知就不再送一遍
                        notices.removeIf(n -> statuses.has(n.agentId()));
                        ObjectNode out = JSON.createObjectNode();
                        out.set("status", statuses);
                        out.put("timed_out", false);
                        return json(out);
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        ObjectNode out = JSON.createObjectNode();
                        out.set("status", JSON.createObjectNode());
                        out.put("timed_out", true);
                        return json(out);
                    }
                    try {
                        lock.wait(Math.max(1, remaining / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();     // 主 agent 被叫停，还给它的工具线程
                        return ToolResult.error("(已取消) 等待被叫停");
                    }
                }
            }
        }
    }

    private final class Close implements Tool {
        @Override
        public String name() {
            return "close_agent";
        }

        @Override
        public String description() {
            return "不再需要时关掉一个子 agent，返回它关之前的状态。做完了的子 agent 也一直开着、占着名额，直到被关掉。"
                    + "不需要的别一直开着。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {"target": {"type": "string", "description": "要关的子 agent id"}},
                      "required": ["target"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String target = Args.requireString(args, "target").strip();
            synchronized (lock) {
                Child child = children.get(target);
                JsonNode previous = status(child);
                if (child != null && !child.closed) {
                    child.closed = true;
                    child.beforeClose = child.state;
                    child.queued.clear();
                    child.interruptWith = null;
                    if (child.running) {
                        child.agent.cancel();
                    } else {
                        child.state = State.SHUTDOWN;
                    }
                    notices.removeIf(n -> n.agentId().equals(target));
                    lock.notifyAll();
                }
                ObjectNode out = JSON.createObjectNode();
                out.set("previous_status", previous);
                return json(out);
            }
        }
    }

    private final class Resume implements Tool {
        @Override
        public String name() {
            return "resume_agent";
        }

        @Override
        public String description() {
            return "按 id 重新打开一个关掉的子 agent，之后就能对它 send_input、wait_agent。它之前的上下文还在。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {"id": {"type": "string", "description": "要重新打开的子 agent id"}},
                      "required": ["id"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String id = Args.requireString(args, "id").strip();
            synchronized (lock) {
                Child child = children.get(id);
                if (child == null) {
                    return ToolResult.error("没有这个子 agent: " + id);
                }
                if (child.closed) {
                    if (open() >= settings.maxThreads()) {
                        return ToolResult.error("已经开着 " + settings.maxThreads() + " 个子 agent 了，先 close_agent 关掉不需要的");
                    }
                    if (child.running) {
                        return ToolResult.error(id + " 还在收尾，稍后再试");
                    }
                    child.closed = false;
                    child.state = child.beforeClose == null || child.beforeClose == State.RUNNING
                            ? State.INTERRUPTED : child.beforeClose;
                }
                ObjectNode out = JSON.createObjectNode();
                out.set("status", status(child));
                return json(out);
            }
        }
    }

    /**
     * 带归属检查的写文件工具。包在 {@code write_file} / {@code edit_file} / {@code create_project} 外面，
     * 每个写入方（主 agent、各个子 agent）各一份，写之前问 {@link #checkWrite}。
     *
     * <p>bash 也能写文件，这里拦不住——和 {@link BuiltinTools#WRITING_TOOLS} 是同一个口径：
     * 绝大多数改动走写文件工具，挡住它就挡住了「两边反复覆盖同一个文件」的主路。
     */
    final class OwnedWrite implements Tool {
        final Tool inner;
        private final String writer;

        OwnedWrite(Tool inner, String writer) {
            this.inner = inner;
            this.writer = writer;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public String parametersSchema() {
            return inner.parametersSchema();
        }

        @Override
        public boolean parallelSafe() {
            return inner.parallelSafe();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            List<String> paths = new ArrayList<>();
            if (args.hasNonNull("path") && args.get("path").isTextual()) {
                paths.add(args.get("path").asText());
            }
            args.path("files").forEach(f -> {
                if (f.path("path").isTextual()) {
                    paths.add(f.path("path").asText());
                }
            });
            args.path("dirs").forEach(d -> {
                if (d.isTextual()) {
                    paths.add(d.asText());
                }
            });
            String refused = checkWrite(writer, paths);
            return refused == null ? inner.execute(args) : ToolResult.error(refused);
        }
    }

    /**
     * 包在主 agent 的 bash / run_tests 外面：跑的是测试命令，就把子 agent 此前的改动记为已验证。
     * 只包主 agent 那一份——子 agent 自己跑测试不算主 agent 验证过。
     */
    final class MainTestWatch implements Tool {
        final Tool inner;

        MainTestWatch(Tool inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public String parametersSchema() {
            return inner.parametersSchema();
        }

        @Override
        public boolean parallelSafe() {
            return inner.parallelSafe();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            ToolResult result = inner.execute(args);
            String command = args.path("command").asText("");
            if (result.ok() && (inner.name().equals("run_tests") || TEST_COMMAND.matcher(command).find())) {
                synchronized (lock) {
                    unverified.clear();
                    reminded.clear();
                }
            }
            return result;
        }
    }

    /** 子 agent 自己的收件箱：主 agent 用 send_input 排队的话。 */
    private final class ChildInbox implements Mailbox {
        private final Child child;

        ChildInbox(Child child) {
            this.child = child;
        }

        @Override
        public List<String> drain() {
            synchronized (lock) {
                List<String> out = child.queued.stream().map(SubAgents::agentMessage).toList();
                child.queued.clear();
                return out;
            }
        }

        @Override
        public boolean hasMail() {
            synchronized (lock) {
                return !child.queued.isEmpty();
            }
        }

        @Override
        public boolean busy() {
            return false;          // 子 agent 没有自己的子 agent
        }

        @Override
        public void awaitChange(long millis) {
        }

        @Override
        public void interruptAll() {
        }

        @Override
        public void shutdown() {
        }
    }
}
