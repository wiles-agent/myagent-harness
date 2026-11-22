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
 * @param pre  {@code PreToolUse}：调工具之前，能拦
 * @param post {@code PostToolUse}：调完之后，拦不住，话交给模型
 */
public record SessionHooks(ToolHooks pre, ToolHooks post) {

    private static final Logger log = LoggerFactory.getLogger(SessionHooks.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final SessionHooks NONE = new SessionHooks(ToolHooks.NONE, ToolHooks.NONE);

    public boolean isEmpty() {
        return pre.isEmpty() && post.isEmpty();
    }

    public static SessionHooks load(Path workspace) {
        return load(Path.of(System.getProperty("user.home"), ".myagent", "settings.json"), workspace);
    }

    static SessionHooks load(Path homeSettings, Path workspace) {
        SessionHooks home = read(homeSettings, workspace);
        Path project = workspace.resolve(".myagent").resolve("settings.json");
        SessionHooks both = project.toAbsolutePath().normalize().equals(homeSettings.toAbsolutePath().normalize())
                ? home : home.plus(read(project, workspace));
        if (!both.isEmpty()) {
            log.info("接入会话级工具 hook");
        }
        return both;
    }

    private SessionHooks plus(SessionHooks more) {
        return new SessionHooks(pre.plus(more.pre), post.plus(more.post));
    }

    /** 读不了、写坏了的整份跳过并记日志——不让启动失败（和 {@link PromptHooks} 同一条纪律）。 */
    private static SessionHooks read(Path file, Path workspace) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            JsonNode hooks = JSON.readTree(file.toFile()).path("hooks");
            return new SessionHooks(ToolHooks.parse(hooks, workspace, ToolHooks.EVENT),
                    ToolHooks.parse(hooks, workspace, ToolHooks.POST));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("{} 里的工具 hook 读不了，整份跳过: {}", file, e.getMessage());
            return NONE;
        }
    }
}
