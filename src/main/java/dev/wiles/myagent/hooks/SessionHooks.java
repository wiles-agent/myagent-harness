package dev.wiles.myagent.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 整个会话的工具 hook（照 Claude Code：settings.json 里的 hooks 管主 agent 和它派出去的所有子 agent）。
 * 家目录 {@code ~/.myagent/settings.json} 和工作区 {@code .myagent/settings.json} 两份合并，家目录的先跑。
 * 子 agent 类型定义里的 hook（{@code agents/*.md}）只管那一种子 agent，另算，在这一层之后跑。
 *
 * @param pre               {@code PreToolUse}：调工具之前，能拦
 * @param post              {@code PostToolUse}：调完之后，拦不住，话交给模型
 * @param permissionRequest {@code PermissionRequest}：要问人之前，能替人回答（{@link #askHooksFirst}）
 * @param permissionDenied  {@code PermissionDenied}：auto 审查拒了之后，能说「可以重试」（{@link #retryOnDenied}）
 */
public record SessionHooks(ToolHooks pre, ToolHooks post, ToolHooks permissionRequest, ToolHooks permissionDenied) {

    public SessionHooks(ToolHooks pre, ToolHooks post) {
        this(pre, post, ToolHooks.NONE, ToolHooks.NONE);
    }

    private static final Logger log = LoggerFactory.getLogger(SessionHooks.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final SessionHooks NONE = new SessionHooks(ToolHooks.NONE, ToolHooks.NONE);

    /** 调工具前后这两种是空的（Agent 用它判要不要绕开）。审批那两种另看。 */
    public boolean isEmpty() {
        return pre.isEmpty() && post.isEmpty();
    }

    private boolean allEmpty() {
        return isEmpty() && permissionRequest.isEmpty() && permissionDenied.isEmpty();
    }

    /**
     * 包在「问人」那一层外面：要问人之前先问 PermissionRequest hook，它表了态就不问人（照 Claude Code）。
     * 只包问人这一层——规则、auto 审查、dontAsk 在它前面已经判过了。
     */
    public dev.wiles.myagent.tool.safety.ApprovalPolicy askHooksFirst(dev.wiles.myagent.tool.safety.ApprovalPolicy human) {
        if (permissionRequest.isEmpty()) {
            return human;
        }
        return request -> {
            ToolHooks.Answer answer = permissionRequest.permission(toolOf(request), inputOf(request));
            if (answer.allow() == null) {
                return human.decide(request);
            }
            if (answer.allow()) {
                return dev.wiles.myagent.tool.safety.ApprovalVerdict.yes();
            }
            return dev.wiles.myagent.tool.safety.ApprovalVerdict.no("被 PermissionRequest hook 拒绝"
                    + (answer.message().isEmpty() ? "" : ": " + answer.message()));
        };
    }

    /** 给 {@code AutoReviewGate#onDenied}：auto 审查拒了之后通知 PermissionDenied hook，它说能不能重试。 */
    public java.util.function.BiPredicate<dev.wiles.myagent.tool.safety.ApprovalRequest, String> retryOnDenied() {
        return (request, reason) -> permissionDenied.retry(toolOf(request), inputOf(request), reason);
    }

    /** 审批请求在 hook 眼里是哪个工具：用我们的名字，matcher 照样认 Claude Code 的叫法（Bash、Write、Read）。 */
    static String toolOf(dev.wiles.myagent.tool.safety.ApprovalRequest request) {
        return switch (request.kind()) {
            case BASH -> "bash";
            case WRITE -> "write_file";
            case READ -> "read_file";
            case NETWORK -> "network";
            case BROWSE -> "browser";
        };
    }

    /** 审批请求换成工具参数的样子：Claude Code 的 hook 认 command / file_path。 */
    static String inputOf(dev.wiles.myagent.tool.safety.ApprovalRequest request) {
        com.fasterxml.jackson.databind.node.ObjectNode input = JSON.createObjectNode();
        switch (request.kind()) {
            case BASH -> input.put("command", request.subject());
            case WRITE, READ -> input.put("file_path", request.subject());
            case NETWORK, BROWSE -> input.put("host", request.subject());
        }
        input.put("reason", request.reason() == null ? "" : request.reason());
        return input.toString();
    }

    public static SessionHooks load(Path workspace) {
        return load(Path.of(System.getProperty("user.home"), ".myagent", "settings.json"), workspace);
    }

    static SessionHooks load(Path homeSettings, Path workspace) {
        SessionHooks home = read(homeSettings, workspace);
        Path project = workspace.resolve(".myagent").resolve("settings.json");
        SessionHooks both = project.toAbsolutePath().normalize().equals(homeSettings.toAbsolutePath().normalize())
                ? home : home.plus(read(project, workspace));
        if (!both.allEmpty()) {
            log.info("接入会话级工具 hook");
        }
        return both;
    }

    private SessionHooks plus(SessionHooks more) {
        return new SessionHooks(pre.plus(more.pre), post.plus(more.post),
                permissionRequest.plus(more.permissionRequest), permissionDenied.plus(more.permissionDenied));
    }

    /** 读不了、写坏了的整份跳过并记日志——不让启动失败（和 {@link PromptHooks} 同一条纪律）。 */
    private static SessionHooks read(Path file, Path workspace) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            JsonNode hooks = JSON.readTree(file.toFile()).path("hooks");
            return new SessionHooks(ToolHooks.parse(hooks, workspace, ToolHooks.EVENT),
                    ToolHooks.parse(hooks, workspace, ToolHooks.POST),
                    ToolHooks.parse(hooks, workspace, ToolHooks.PERMISSION_REQUEST),
                    ToolHooks.parse(hooks, workspace, ToolHooks.PERMISSION_DENIED));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("{} 里的工具 hook 读不了，整份跳过: {}", file, e.getMessage());
            return NONE;
        }
    }
}
