package dev.wiles.myagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.StepBudget;
import dev.wiles.myagent.agent.SubAgents;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.TokenLedger;
import dev.wiles.myagent.context.TokenMeter;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.mcp.McpConfig;
import dev.wiles.myagent.mcp.McpServers;
import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.review.ReviewLoop;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolSearchTool;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无头单次运行：读一段提示词，跑完就退。给评测框架和沙盒用的入口。
 *
 * <h3>为什么不走 --serve + WebSocket</h3>
 * 在别人的容器里（AsynCodeBench 这类评测）只有一条 {@code execute_command}，
 * 端口映射不由我们定。一个进程从头跑到尾、结果在 stdout、退出码说成败，
 * 是最不挑环境的接口——Claude Code 的 {@code -p} 就是这个形状。
 *
 * <h3>危险动作一律自动批准</h3>
 * 没有人在看，问了也没人答。所以这个入口只能在沙盒里用，帮助里写明了。
 * 不接 MCP、不读技能：评测要的是可复现，外挂越少越好。
 */
public final class Headless {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Headless() {
    }

    /**
     * 每次工具调用打一行到 stderr。无头模式没有界面，事后能看的只有这份日志——
     * 「t1 为什么 40 秒撞了 16 步」这种问题，没有它就只能猜。
     */
    private static void putLedger(ObjectNode summary, TokenLedger ledger, long started) {
        summary.put("promptTokens", ledger.promptTokens());
        summary.put("cachedPromptTokens", ledger.cachedPromptTokens());
        summary.put("completionTokens", ledger.completionTokens());
        summary.put("requests", ledger.requests());
        summary.put("durationMs", System.currentTimeMillis() - started);
    }

    /**
     * MYAGENT_TRANSCRIPT=<文件>：把整段对话落盘——模型每段回复的原话、调了什么、工具回了什么（截断），
     * 外加系统提示词和工具菜单。评测里要回答「它为什么不派子 agent」，光有工具调用的 trace 不够：
     * 得看得见它说了什么、菜单上到底有没有那几个工具。
     */
    private static void writeTranscript(Agent agent, ToolRegistry tools) {
        String path = System.getenv("MYAGENT_TRANSCRIPT");
        if (path == null || path.isBlank() || agent == null) {
            return;
        }
        ObjectNode out = JSON.createObjectNode();
        tools.all().forEach(t -> out.withArray("menu").add(t.name()));
        out.put("systemPrompt", agent.systemPrompt());
        var messages = out.putArray("messages");
        for (dev.wiles.myagent.llm.ChatMessage m : agent.conversation()) {
            ObjectNode n = messages.addObject();
            n.put("role", m.role());
            String content = m.content() == null ? "" : m.content();
            n.put("content", "tool".equals(m.role()) && content.length() > 2000
                    ? content.substring(0, 2000) + "…（截断，共 " + content.length() + " 字符）" : content);
            if (m.hasToolCalls()) {
                for (dev.wiles.myagent.llm.ToolCall c : m.toolCalls()) {
                    String args = c.argumentsJson() == null ? "" : c.argumentsJson();
                    n.withArray("toolCalls").addObject().put("name", c.name())
                            .put("args", args.length() > 1500 ? args.substring(0, 1500) + "…" : args);
                }
            }
        }
        try {
            Files.writeString(Path.of(path), out.toPrettyString());
        } catch (IOException e) {
            System.err.println("写不了对话记录 " + path + ": " + e.getMessage());
        }
    }

    private static void writeSummary(String path, String line) {
        try {
            Files.writeString(Path.of(path), line + "\n");
        } catch (IOException e) {
            System.err.println("写不了摘要 " + path + ": " + e.getMessage());
        }
    }

    private static void trace(String stage, AgentEvent event) {
        if (event instanceof AgentEvent.ToolStarted t) {
            String args = t.arguments() == null ? "" : t.arguments().replaceAll("\\s+", " ");
            System.err.println("[trace] " + stage + " → " + t.tool() + " "
                    + (args.length() > 160 ? args.substring(0, 160) + "…" : args));
        } else if (event instanceof AgentEvent.ToolFinished t && !t.ok()) {
            String out = t.output() == null ? "" : t.output().replaceAll("\\s+", " ");
            System.err.println("[trace] " + stage + " ✗ " + t.tool() + " "
                    + (out.length() > 200 ? out.substring(0, 200) + "…" : out));
        } else if (event instanceof AgentEvent.Failed f) {
            System.err.println("[trace] " + stage + " FAILED " + f.reason());
        }
    }

    /** 自动审查用的模型：默认和会话同一个，MYAGENT_AUTO_MODEL 可换；不开思考。 */
    private static dev.wiles.myagent.config.Config autoReviewConfig(dev.wiles.myagent.config.Config config) {
        String model = System.getenv("MYAGENT_AUTO_MODEL");
        var c = model == null || model.isBlank() ? config : config.withModel(model.strip());
        return c.thinking() != null ? c.withThinking(false) : c;
    }

    /** @return 进程退出码：0 正常收口，1 没做完（撞上限 / 验收没过 / 模型失败），2 参数或配置错 */
    public static int run(CliOptions options) {
        String prompt;
        try {
            prompt = "-".equals(options.once())
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(options.once()));
        } catch (IOException e) {
            System.err.println("读不了提示词: " + e.getMessage());
            return 2;
        }
        if (prompt.isBlank()) {
            System.err.println("提示词是空的");
            return 2;
        }

        Config loaded = Config.load();
        Config config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model(),
                        loaded.contextTokens(), loaded.thinking());
        if (!config.hasApiKey()) {
            System.err.println("没有 API key：配 ~/.myagent/config.json 或环境变量 DEEPSEEK_API_KEY");
            return 2;
        }

        Workspace workspace = new Workspace(options.workspace());
        MemoryStore memory = MemoryStore.inUserHome();
        // 无头没人可问：下一层是「全都批」。auto / dontAsk 的子 agent 由 AutoReviewGate 先截住，
        // 审查交还给人时这里只能拒（交给「全都批」等于放行）
        dev.wiles.myagent.hooks.SessionHooks sessionHooks = dev.wiles.myagent.hooks.SessionHooks.load(workspace.root());
        ApprovalPolicy headlessApproval = new dev.wiles.myagent.tool.safety.AutoReviewGate(
                sessionHooks.askHooksFirst(ApprovalPolicy.approveAll()), false)
                .onDenied(sessionHooks.retryOnDenied());
        ToolRegistry tools = BuiltinTools.createDefault(workspace, headlessApproval, memory);
        // MCP 默认不接：评测靠这条路径跑，结果不能随本机 ~/.myagent/mcp.json 变。
        // MYAGENT_MCP=on 才接，拿来比「接了某个 MCP server 前后」——和 MYAGENT_SUBAGENTS 同一种对照开关。
        // =eager 也接，但不延迟加载：工具说明直接进菜单。延迟时模型手里有 grep 就不会去 tool_search，
        // 想测「用上了这个 server 会怎样」得先让它看得见
        String mcpMode = System.getenv("MYAGENT_MCP");
        boolean mcpEager = "eager".equalsIgnoreCase(mcpMode);
        McpServers mcp = McpServers.start("on".equalsIgnoreCase(mcpMode) || mcpEager
                ? McpConfig.load() : new McpConfig(Map.of()));
        tools.registerAll(mcp.tools());
        TokenMeter meter = new TokenMeter();
        java.util.function.Supplier<LlmClient> clients = () -> meter.meter(new DeepSeekClient(config));
        int maxSteps = options.maxSteps() > 0 ? options.maxSteps() : Agent.DEFAULT_MAX_STEPS;

        long started = System.currentTimeMillis();
        ObjectNode summary = JSON.createObjectNode();
        summary.put("mode", options.onceMode());
        summary.put("model", config.model());
        summary.put("maxSteps", maxSteps);
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<SubAgents> live = new AtomicReference<>();
        AtomicReference<Agent> liveAgent = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
        // 评测框架到点会发 SIGTERM（或者包一层 timeout）：被杀时也把截至此刻的账写出来，
        // 不然跑了四十分钟的那一轮 token、缓存命中、派过的子 agent 全都没有记录
        if (options.onceOut() != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!written.compareAndSet(false, true)) {
                    return;
                }
                ObjectNode partial = JSON.createObjectNode();
                partial.put("mode", options.onceMode());
                partial.put("model", config.model());
                partial.put("maxSteps", maxSteps);
                partial.put("status", "TERMINATED");
                partial.put("steps", steps.get());
                SubAgents sub = live.get();
                partial.put("subagents", sub == null ? 0 : sub.spawned());
                if (sub != null) {
                    partial.set("subagentReport", sub.report());
                }
                putLedger(partial, meter.snapshot(), started);
                writeSummary(options.onceOut(), partial.toString());
                writeTranscript(liveAgent.get(), tools);
            }, "once-summary-on-exit"));
        }
        int exit;
        String text;
        try {
            MemoryIndex index = memory.index(workspace.root(),
                    MemoryIndex.budgetFor(config.contextTokens()));
            // 子 agent：spawn_agent 异步派出、结果以通知送回，worker 能直接改工作区。
            // 派不派由模型按工具说明自己定（照 Claude Code）；MYAGENT_SUBAGENTS=off 关掉，评测做对照用
            AtomicInteger subagentSteps = new AtomicInteger();
            SubAgents.Mode subMode = SubAgents.Mode.parse(System.getenv("MYAGENT_SUBAGENTS"));
            // MYAGENT_TOTAL_STEPS：主 agent 和子 agent 共用的步数预算（算力对齐的对照用），不设就不共用
            int totalSteps = SubAgents.Settings.intOr(System.getenv("MYAGENT_TOTAL_STEPS"), 0);
            StepBudget budget = totalSteps > 0 ? new StepBudget(totalSteps) : null;
            SubAgents.Settings subSettings = SubAgents.Settings.fromEnv(System::getenv, maxSteps, budget);
            boolean withSubagents = subMode != SubAgents.Mode.OFF;
            SubAgents subagents = !withSubagents ? null
                    : new SubAgents(clients, tools, workspace.root(), () -> new ContextManager(config.contextTokens()),
                            subSettings, subMode, (id, event) -> {
                                if (event instanceof AgentEvent.Thinking) {
                                    subagentSteps.incrementAndGet();
                                }
                                trace(id, event);
                            });
            if (subagents != null) {
                tools.registerAll(subagents.tools());
                subagents.withClientFactory((model, thinking) -> meter.meter(new DeepSeekClient(
                            config.withModel(model).withThinking(thinking))), config.thinking() != null)
                        .withDefinitions(dev.wiles.myagent.agent.AgentDefinitions.forHeadless(System::getenv,
                                workspace.root(), Path.of(System.getProperty("user.home"))))
                        .withIsolatedTools(wtRoot -> BuiltinTools.createDefault(
                                new dev.wiles.myagent.tool.Workspace(wtRoot), headlessApproval, memory))
                        .withAutoReviewer((delegation, calls) -> new dev.wiles.myagent.tool.safety.AutoClassifier(
                                meter.meter(new DeepSeekClient(autoReviewConfig(config)).temperature(0)),
                                () -> new dev.wiles.myagent.tool.safety.AutoClassifier.Context(
                                        java.util.List.of(prompt), delegation, calls.get()), dev.wiles.myagent.tool.safety.AutoModeSettings.inUserHome()))
                        .withMcpLauncher(servers -> {
                        // 类型定义里内联的 MCP server：和 mcp.json 里的同一种定义，派出时单独拉起
                        java.util.Map<String, dev.wiles.myagent.mcp.McpConfig.ServerSpec> specs = new java.util.LinkedHashMap<>();
                        servers.forEach((name, node) -> {
                            var spec = dev.wiles.myagent.mcp.McpConfig.spec(name, node);
                            if (spec != null) {
                                specs.put(name, spec);
                            }
                        });
                        var childServers = McpServers.start(new dev.wiles.myagent.mcp.McpConfig(specs));
                        return new SubAgents.McpHandle(childServers.tools(), childServers::close);
                    });
                // 读了自定义类型，就也接上技能库：定义里的 skills 要靠它预载。没打开就不读，和类型同一个开关
                if (dev.wiles.myagent.agent.AgentDefinitions.headlessOptIn(System::getenv)) {
                    var skillLibrary = dev.wiles.myagent.skill.SkillLibrary.load(workspace.root());
                    subagents.withSkills(skillLibrary::find);
                }
            }
            // 和界面那条路径一样：MCP 工具平时只露名字，用到时 tool_search 拉完整说明。
            // 没接 MCP 时这里什么都不做，菜单和以前一字不差
            if (!mcp.tools().isEmpty()) {
                if (!mcpEager) {
                    tools.defer(mcp.tools().stream().map(Tool::name).toList());
                    tools.register(new ToolSearchTool(tools));
                }
                summary.put("mcpTools", mcp.tools().size());
                summary.put("mcpEager", mcpEager);
            }
            Agent agent = new Agent(clients.get(), tools,
                    SystemPrompt.forWorkspace(workspace.root(), index, false),
                    maxSteps, new ContextManager(config.contextTokens()))
                    .withWritingTools(BuiltinTools.WRITING_TOOLS)
                    .withToolHooks(sessionHooks)
                    .withStepBudget(budget);
            liveAgent.set(agent);
            if (budget != null) {
                summary.put("totalStepBudget", budget.total());
            }
            if (subagents != null) {
                subagents.attach(agent);
                live.set(subagents);
            }
            AtomicReference<String> failed = new AtomicReference<>();
            agent.onEvent(event -> {
                if (event instanceof AgentEvent.Thinking) {
                    steps.incrementAndGet();
                } else if (event instanceof AgentEvent.Failed f) {
                    failed.set(f.reason());
                }
                trace("agent", event);
            });
            // MYAGENT_PERMISSION_MODE=auto（照 claude -p --permission-mode auto）：主会话要问人的操作交给自动审查。
            // 无头没人可问，审查交还给人时直接拒（见 AutoReviewGate）；子 agent 跟着进 auto
            if ("auto".equals(System.getenv("MYAGENT_PERMISSION_MODE"))) {
                dev.wiles.myagent.tool.safety.ApprovalScope.set(null, false,
                        new dev.wiles.myagent.tool.safety.AutoClassifier(
                                meter.meter(new DeepSeekClient(autoReviewConfig(config)).temperature(0)),
                                () -> new dev.wiles.myagent.tool.safety.AutoClassifier.Context(java.util.List.of(prompt),
                                        null, dev.wiles.myagent.tool.safety.AutoClassifier.toolCallsOf(agent.conversation())), dev.wiles.myagent.tool.safety.AutoModeSettings.inUserHome()));
                summary.put("permissionMode", "auto");
            }
            if ("react+review".equals(options.onceMode())) {
                AtomicInteger reviewSteps = new AtomicInteger();
                ReviewLoop loop = new ReviewLoop(clients, tools, workspace.root(), (stage, event) -> {
                    if (event instanceof AgentEvent.Thinking) {
                        reviewSteps.incrementAndGet();
                    }
                    trace(stage.id(), event);
                });
                ReviewLoop.Outcome outcome = loop.run(new ReviewLoop.MainTurn() {
                    @Override
                    public String run(String input) {
                        return agent.run(input);
                    }

                    @Override
                    public boolean failed() {
                        return failed.get() != null;
                    }
                }, prompt);
                text = outcome.text();
                summary.put("reviews", outcome.reviews());
                summary.put("reviewSteps", reviewSteps.get());
                summary.put("reviewRestored", outcome.restored());
                outcome.verdicts().forEach(summary.withArray("reviewVerdicts")::add);
                outcome.scores().forEach(summary.withArray("reviewScores")::add);
            } else {
                text = agent.run(prompt);
            }
            if (subagents != null && !subagents.stopAll(15_000)) {
                // 进程马上要退，还在跑的子 agent 会被腰斩在半路——记一笔，评测时好对账
                summary.put("subagentsStillRunning", true);
            }
            summary.put("status", failed.get() == null ? "COMPLETED" : "FAILED");
            if (failed.get() != null) {
                summary.put("failed", failed.get());
            }
            summary.put("steps", steps.get());
            summary.put("subagentMode", subMode.name().toLowerCase());
            summary.put("subagents", subagents == null ? 0 : subagents.spawned());
            if (subagents != null) {
                // 写入冲突、收尾验证提醒、每个子 agent 实际写了什么跑了什么——评测归因用
                summary.set("subagentReport", subagents.report());
            }
            if (budget != null) {
                summary.put("totalStepsUsed", budget.used());
            }
            summary.put("subagentSteps", subagentSteps.get());
            exit = failed.get() == null ? 0 : 1;
        } catch (RuntimeException e) {
            text = "";
            summary.put("status", "ERROR");
            summary.put("failed", e.getClass().getSimpleName() + ": " + e.getMessage());
            exit = 1;
        } finally {
            mcp.close();     // MCP server 是子进程，不关就是孤儿
        }
        putLedger(summary, meter.snapshot(), started);

        System.out.println(text);
        System.out.flush();
        String line = summary.toString();
        if (options.onceOut() != null) {
            if (written.compareAndSet(false, true)) {        // 退出钩子已经写过（正在被杀）就不再覆盖
                writeSummary(options.onceOut(), line);
                writeTranscript(liveAgent.get(), tools);
            }
        } else {
            System.err.println(line);
        }
        return exit;
    }
}
