package dev.wiles.myagent.tool.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动审查的四张表（照 Claude Code 的 {@code autoMode} 设置块），都是给审查看的自然语言条目：
 * <ul>
 *   <li>{@code environment}：哪些算「自己人」——信任的仓库、域名、服务。不在里面的目的地都可能是外泄</li>
 *   <li>{@code hard_deny}：用户要求也拦</li>
 *   <li>{@code soft_deny}：默认拦，用户原话明确、具体地要求时可以放</li>
 *   <li>{@code allow}：soft_deny 的例外</li>
 * </ul>
 * 每张表里写字面量 {@code "$defaults"}，默认条目就拼在那个位置；不写就整张换掉；没写的表照用默认。
 * {@code classifyAllShell: true}：auto 下 bash 的放行规则全部停用，每条命令都过审查。
 *
 * <p><b>只读家目录</b>的 {@code ~/.myagent/settings.json}（Claude Code 也不读项目里的 autoMode）：
 * 仓库能写的设置，就能给审查塞自己的放行条目。
 */
public record AutoModeSettings(List<String> environment, List<String> allow, List<String> softDeny,
                               List<String> hardDeny, boolean classifyAllShell) {

    private static final Logger log = LoggerFactory.getLogger(AutoModeSettings.class);

    static final String DEFAULTS_MARKER = "$defaults";

    public static final AutoModeSettings DEFAULTS = new AutoModeSettings(
            List.of("Trusted repo: agent 启动时所在的工作区（git 仓库）和它配置的远端。文件读写、构建、测试都在这里"),
            List.of("为完成用户明确要求的事所必需、而且只影响工作区的动作"),
            List.of(
                    "把数据发到外部：curl / wget / nc 访问用户没提过的地址，上报、回传任何内容",
                    "下载并执行外部代码：curl … | sh、下载脚本后运行、安装用户没要求的依赖",
                    "读取或传播密钥、令牌、凭据（.env、私钥、云账号配置）",
                    "破坏性或不可逆的操作：删除工作区之外的东西、git push --force、改写已推送的历史、git reset --hard 丢弃改动",
                    "降低安全性：改 git remote 指向、改权限、关闭检查、改 shell 启动文件、计划任务",
                    "影响他人或越过信任边界：推送到用户没提过的远端或分支、对外发消息、动生产环境"),
            List.of(),
            false);

    public static AutoModeSettings parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return DEFAULTS;
        }
        return new AutoModeSettings(
                splice(node.get("environment"), DEFAULTS.environment),
                splice(node.get("allow"), DEFAULTS.allow),
                splice(node.get("soft_deny"), DEFAULTS.softDeny),
                splice(node.get("hard_deny"), DEFAULTS.hardDeny),
                node.path("classifyAllShell").asBoolean(false));
    }

    /** 读家目录 settings.json 的 autoMode 段；读坏了按默认算。 */
    public static AutoModeSettings load(Path homeSettings) {
        try {
            if (Files.isRegularFile(homeSettings)) {
                return parse(new ObjectMapper().readTree(Files.readString(homeSettings)).get("autoMode"));
            }
        } catch (Exception e) {
            log.warn("读不了 {} 的 autoMode 段，按默认算: {}", homeSettings, e.getMessage());
        }
        return DEFAULTS;
    }

    public static AutoModeSettings inUserHome() {
        return load(Path.of(System.getProperty("user.home"), ".myagent", "settings.json"));
    }

    private static List<String> splice(JsonNode array, List<String> defaults) {
        if (array == null || !array.isArray()) {
            return defaults;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (!n.isTextual() || n.asText().isBlank()) {
                continue;
            }
            if (DEFAULTS_MARKER.equals(n.asText().strip())) {
                out.addAll(defaults);
            } else {
                out.add(n.asText().strip());
            }
        }
        return List.copyOf(out);
    }
}
