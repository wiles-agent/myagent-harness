package dev.wiles.myagent.tool.sandbox;

import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 在沙箱里跑一条命令：建这次的临时目录、起出网代理、包一层沙箱、跑完收拾干净。
 *
 * <p>代理问「这个主机能不能连」走的是同一条审批链（{@link ApprovalRequest#network}）：手动模式问人，
 * auto 下交给审查，dontAsk 拒。代理的线程是在调命令的线程上开的，继承它的审批来源——
 * 问出去的框上写的是「哪个子 agent 的命令要联网」，auto 的审查器也是那个子 agent 的。
 */
public final class SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxRunner.class);

    /** 拿着包好的命令真正去跑。 */
    @FunctionalInterface
    public interface Exec<T> {
        T run(List<String> argv) throws IOException;
    }

    private final SandboxSettings settings;
    private final Sandbox sandbox;
    private final ApprovalPolicy approval;

    public SandboxRunner(SandboxSettings settings, Sandbox sandbox, ApprovalPolicy approval) {
        this.settings = settings;
        this.sandbox = sandbox;
        this.approval = approval;
        if (settings.enabled() && sandbox.unavailable() != null) {
            log.warn("沙箱开了但用不了，命令照常在沙箱外跑（照 Claude Code 的默认；要启动即失败设 failIfUnavailable）: {}",
                    sandbox.unavailable());
        }
    }

    public static SandboxRunner off() {
        return new SandboxRunner(SandboxSettings.OFF, Sandbox.forPlatform("none", c -> false), ApprovalPolicy.denyAll());
    }

    /** 这台机器上的沙箱，设置读家目录。 */
    public static SandboxRunner forHost(ApprovalPolicy approval) {
        return new SandboxRunner(SandboxSettings.inUserHome(), Sandbox.current(), approval);
    }

    /** 开了，而且这个平台用得上。 */
    public boolean active() {
        return settings.enabled() && sandbox.unavailable() == null;
    }

    public SandboxSettings settings() {
        return settings;
    }

    /**
     * 沙箱里的 /tmp：一个 runner（一段会话）一个，跨命令留着（照 Claude Code 的 per-user temp）——
     * 上一条下载到 /tmp 的东西下一条还要用。进程退出时收掉。
     */
    private Path scratch;

    private synchronized Path scratch() throws IOException {
        if (scratch == null || !Files.isDirectory(scratch)) {
            Path dir = Files.createTempDirectory("myagent-sbx-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir), "sandbox-tmp-cleanup"));
            scratch = dir;
        }
        return scratch;
    }

    public <T> T run(List<String> argv, Path workspace, Exec<T> exec) throws IOException {
        Path scratch = scratch();
        Path net = Files.createTempDirectory("myagent-net-");
        NetworkProxy.HostDecider decider = host -> approval.decide(
                ApprovalRequest.network(host, "沙箱里的命令要连 " + host + "（不在放行的域名里）")).approved();
        NetworkProxy proxy = sandbox.wantsTcpProxy() ? NetworkProxy.tcp(settings, decider)
                : NetworkProxy.unix(net.resolve("p.sock"), settings, decider);
        try (proxy) {
            return exec.run(sandbox.wrap(argv, workspace, scratch, proxy.endpoint(), settings));
        } finally {
            deleteTree(net);                    // 代理的 socket 每条命令一个；/tmp 留着
        }
    }

    private static void deleteTree(Path dir) {
        try (var all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录，删不掉系统会收
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
