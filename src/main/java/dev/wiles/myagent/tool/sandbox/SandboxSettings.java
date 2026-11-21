package dev.wiles.myagent.tool.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 沙箱设置，键名照 Claude Code 的 {@code sandbox.*}（见 docs/guide.md「沙箱」）。
 *
 * <h3>只认家目录</h3>
 * 读 {@code ~/.myagent/settings.json} 的 {@code sandbox} 段，外加环境变量 {@code MYAGENT_SANDBOX=on|off}。
 * 工作区里的设置不读：仓库能写的设置就能把沙箱关掉、把出网名单放宽——和 policy.json 只认家目录同一个道理。
 *
 * @param enabled                  开没开（默认关，照 Claude Code）
 * @param failIfUnavailable        平台不支持时启动就失败，而不是不进沙箱照跑
 * @param allowUnsandboxedCommands 允不允许「在沙箱外重跑」（dangerously_disable_sandbox）；关掉就忽略那个参数
 * @param autoAllow                沙箱里跑的命令不再问人（deny 规则照样拦；auto 下照样过审查）
 * @param allowWrite               工作区和临时目录之外，还允许写的目录
 * @param allowedDomains           代理直接放行的域名（{@code *.example.com} 连主域名一起）
 * @param deniedDomains            哪种模式下都拒的域名
 * @param strictAllowlist          不在放行名单里的一律拒，不问人
 */
public record SandboxSettings(boolean enabled, boolean failIfUnavailable, boolean allowUnsandboxedCommands,
                              boolean autoAllow, List<String> allowWrite, List<String> allowedDomains,
                              List<String> deniedDomains, boolean strictAllowlist) {

    private static final Logger log = LoggerFactory.getLogger(SandboxSettings.class);

    public static final SandboxSettings OFF = new SandboxSettings(false, false, true, true,
            List.of(), List.of(), List.of(), false);

    /** @param sandbox settings.json 里的 {@code sandbox} 段；没有就是 null */
    public static SandboxSettings parse(JsonNode sandbox) {
        if (sandbox == null || !sandbox.isObject()) {
            return OFF;
        }
        JsonNode fs = sandbox.path("filesystem");
        JsonNode net = sandbox.path("network");
        return new SandboxSettings(
                sandbox.path("enabled").asBoolean(false),
                sandbox.path("failIfUnavailable").asBoolean(false),
                sandbox.path("allowUnsandboxedCommands").asBoolean(true),
                sandbox.path("autoAllow").asBoolean(true),
                strings(fs.path("allowWrite")),
                strings(net.path("allowedDomains")),
                strings(net.path("deniedDomains")),
                net.path("strictAllowlist").asBoolean(false));
    }

    /** 读家目录的 settings.json；{@code MYAGENT_SANDBOX=on|off} 压过它。读坏了按没开算。 */
    public static SandboxSettings load(Path homeSettings, Function<String, String> env) {
        SandboxSettings s = OFF;
        try {
            if (Files.isRegularFile(homeSettings)) {
                s = parse(new ObjectMapper().readTree(Files.readString(homeSettings)).get("sandbox"));
            }
        } catch (Exception e) {
            log.warn("读不了 {} 的 sandbox 段，按没开算: {}", homeSettings, e.getMessage());
        }
        String flag = env.apply("MYAGENT_SANDBOX");
        if ("on".equals(flag) || "off".equals(flag)) {
            s = new SandboxSettings("on".equals(flag), s.failIfUnavailable, s.allowUnsandboxedCommands, s.autoAllow,
                    s.allowWrite, s.allowedDomains, s.deniedDomains, s.strictAllowlist);
        }
        return s;
    }

    /**
     * 启动时该不该直接失败（照 Claude Code 的 failIfUnavailable）：开了、要求必须有、这个平台又用不了。
     *
     * @return 失败的原因；不用失败返回 null
     */
    public String startupProblem(Sandbox sandbox) {
        if (!enabled || !failIfUnavailable || sandbox.unavailable() == null) {
            return null;
        }
        return "沙箱设了 failIfUnavailable，但这里用不了：" + sandbox.unavailable();
    }

    public static SandboxSettings inUserHome() {
        return load(Path.of(System.getProperty("user.home"), ".myagent", "settings.json"), System::getenv);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText().strip());
                }
            });
        }
        return List.copyOf(out);
    }
}
