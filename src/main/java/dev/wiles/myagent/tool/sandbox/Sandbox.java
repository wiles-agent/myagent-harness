package dev.wiles.myagent.tool.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 操作系统强制的边界，套在 agent 跑的 shell 命令外面（照 Claude Code 的沙箱）。
 *
 * <h3>为什么要它</h3>
 * 命令守卫和审批规则看的是<b>命令文本</b>：{@code /usr/bin/curl}、{@code sh -c 'curl …'}、
 * {@code python -c "urllib…"} 都能绕过按文本写的规则——Claude Code 文档原话「a Bash rule isn't a security boundary」。
 * 沙箱不看文本，看进程实际能碰什么：写只能写工作区和临时目录，网络只能走一个按域名放行的代理。
 *
 * <h3>平台（照 Claude Code）</h3>
 * Linux / WSL2 用 bubblewrap，macOS 用 Seatbelt。<b>原生 Windows 不支持</b>，要用的话在 WSL2 里跑。
 *
 * <h3>只管 shell 命令</h3>
 * 读写文件的工具、MCP、hook 不在沙箱里（和 Claude Code 一样）：它们有自己的闸（工作区越界、写入审批）。
 */
public interface Sandbox {

    /** 沙箱里的代理端口：沙箱里的程序连 127.0.0.1:这个端口，就到了外面的代理。 */
    int PROXY_PORT = 3128;

    /**
     * 在可写目录里也只读的路径（照 Claude Code 的 protected paths）：改了它们，就能让沙箱外的东西
     * 执行沙箱里写的代码——git 钩子、agent 自己的配置和类型定义、shell 启动文件。
     *
     * <p>本来不存在的：bubblewrap 拿 /dev/null 占住它（{@link #masked}），沙箱里建不出来；跑完由
     * {@link SandboxRunner} 收掉 bwrap 留在工作区里的空挂载点。Seatbelt 的拒写规则对不存在的路径本来就管用。
     */
    List<String> PROTECTED = List.of(".git/hooks", ".git/config", ".myagent", ".claude", ".mcp.json",
            ".bashrc", ".zshrc", ".profile", ".gitconfig");

    /** 代理在哪：Linux 走 Unix socket（沙箱里没有网络命名空间之外的路），macOS 走本机端口。 */
    record Endpoint(Path socket, int port) {
        public static Endpoint unix(Path socket) {
            return new Endpoint(socket, 0);
        }

        public static Endpoint tcp(int port) {
            return new Endpoint(null, port);
        }
    }

    String name();

    /** 用不了的原因；能用返回 null。 */
    String unavailable();

    /** macOS 的代理要监听本机端口，Linux 的要 Unix socket。 */
    default boolean wantsTcpProxy() {
        return false;
    }

    /**
     * 把一条命令包进沙箱。
     *
     * @param argv      原命令
     * @param workspace 工作区（可写）
     * @param scratch   这次命令的临时目录（沙箱里的 /tmp）
     * @param proxy     网络代理；null 表示不通网
     */
    List<String> wrap(List<String> argv, Path workspace, Path scratch, Endpoint proxy, SandboxSettings settings);

    /** @param hasExecutable 判断某个命令在不在 PATH 上（测试里可替换） */
    static Sandbox forPlatform(String osName, Predicate<String> hasExecutable) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return hasExecutable.test("bwrap") ? new Bubblewrap(hasExecutable.test("socat"))
                    : unsupported("没装 bubblewrap（bwrap）。装上它（apt install bubblewrap socat）才能用沙箱");
        }
        if (os.contains("mac")) {
            return hasExecutable.test("sandbox-exec") ? new Seatbelt() : unsupported("找不到 sandbox-exec");
        }
        return unsupported("原生 " + osName + " 不支持沙箱（照 Claude Code）。要用沙箱，在 WSL2 里跑");
    }

    static Sandbox current() {
        return forPlatform(System.getProperty("os.name", ""), Sandbox::onPath);
    }

    static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    private static Sandbox unsupported(String reason) {
        return new Sandbox() {
            public String name() {
                return "none";
            }

            public String unavailable() {
                return reason;
            }

            public List<String> wrap(List<String> argv, Path ws, Path scratch, Endpoint proxy, SandboxSettings s) {
                return argv;
            }
        };
    }

    /**
     * 这个沙箱要拿占位挡住的受保护路径（本来不存在的那些）。跑完命令要收掉占位留下的空挂载点。
     * 默认没有：只有 bubblewrap 需要。
     */
    default List<Path> masked(Path workspace) {
        return List.of();
    }

    /**
     * 工作区里本来不存在的受保护路径。还不是 git 仓库时 {@code .git/...} 不算——占住 .git 会让 git init 都跑不了；
     * 而 init 之后下一条命令起 .git/hooks 就在了，照常只读。
     */
    static List<Path> missingProtected(Path workspace) {
        List<Path> out = new ArrayList<>();
        for (String p : PROTECTED) {
            Path path = workspace.resolve(p);
            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(path.getParent())) {
                continue;                           // 上一级都没有（还没 git init）：不占
            }
            out.add(path);
        }
        return out;
    }

    /** 工作区里所有受保护路径，不管在不在（Seatbelt 的拒写规则用）。 */
    static List<Path> allProtected(Path workspace) {
        return PROTECTED.stream().map(workspace::resolve).toList();
    }

    /** 工作区里已经存在的受保护路径。 */
    static List<Path> protectedIn(Path workspace) {
        List<Path> out = new ArrayList<>();
        for (String p : PROTECTED) {
            Path path = workspace.resolve(p);
            if (Files.exists(path)) {
                out.add(path);
            }
        }
        return out;
    }

    /** Linux / WSL2：bubblewrap。 */
    final class Bubblewrap implements Sandbox {
        private final boolean socat;

        Bubblewrap(boolean socat) {
            this.socat = socat;
        }

        public String name() {
            return "bubblewrap";
        }

        public String unavailable() {
            return null;
        }

        @Override
        public List<Path> masked(Path workspace) {
            return missingProtected(workspace);
        }

        public List<String> wrap(List<String> argv, Path ws, Path scratch, Endpoint proxy, SandboxSettings s) {
            List<String> a = new ArrayList<>(List.of("bwrap", "--die-with-parent", "--new-session",
                    "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                    // 先挂 /tmp 再挂工作区：工作区在 /tmp 底下时不会被盖住
                    "--bind", scratch.toString(), "/tmp",
                    "--bind", ws.toString(), ws.toString()));
            for (String dir : s.allowWrite()) {
                a.addAll(List.of("--bind-try", dir, dir));
            }
            // 受保护路径挂在工作区之后，才盖得住
            for (Path p : protectedIn(ws)) {
                a.addAll(List.of("--ro-bind", p.toString(), p.toString()));
            }
            // 不存在的拿 /dev/null 占住：mkdir 撞 EEXIST，往里写撞 ENOTDIR，直接写撞只读
            for (Path p : masked(ws)) {
                a.addAll(List.of("--ro-bind", "/dev/null", p.toString()));
            }
            a.add("--unshare-net");                 // 单独的网络命名空间：只有回环，没有出去的路
            boolean bridged = proxy != null && proxy.socket() != null && socat;
            if (bridged) {
                Path dir = proxy.socket().getParent();
                a.addAll(List.of("--bind", dir.toString(), dir.toString()));
                String url = "http://127.0.0.1:" + PROXY_PORT;
                for (String var : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy")) {
                    a.addAll(List.of("--setenv", var, url));
                }
                a.addAll(List.of("--unsetenv", "NO_PROXY", "--unsetenv", "no_proxy"));
            }
            a.addAll(List.of("--chdir", ws.toString(), "--"));
            if (bridged) {
                // 沙箱里唯一的出路：socat 在 127.0.0.1 上听着，转给外面代理的 Unix socket
                a.addAll(List.of("sh", "-c",
                        "socat TCP-LISTEN:" + PROXY_PORT + ",bind=127.0.0.1,fork,reuseaddr UNIX-CONNECT:"
                                + proxy.socket() + " 2>/dev/null & p=$!; sleep 0.2; \"$@\"; r=$?; kill $p 2>/dev/null; exit $r",
                        "sh"));
            }
            a.addAll(argv);
            return a;
        }
    }

    /** macOS：Seatbelt（sandbox-exec）。没在真机上验证过，见 docs/guide.md。 */
    final class Seatbelt implements Sandbox {
        public String name() {
            return "seatbelt";
        }

        public String unavailable() {
            return null;
        }

        @Override
        public boolean wantsTcpProxy() {
            return true;
        }

        public List<String> wrap(List<String> argv, Path ws, Path scratch, Endpoint proxy, SandboxSettings s) {
            StringBuilder p = new StringBuilder("(version 1)\n(allow default)\n(deny file-write*)\n");
            p.append("(allow file-write* (subpath ").append(q(ws)).append("))\n");
            p.append("(allow file-write* (subpath ").append(q(scratch)).append("))\n");
            p.append("(allow file-write* (literal \"/dev/null\") (literal \"/dev/tty\") (subpath \"/dev/fd\"))\n");
            for (String dir : s.allowWrite()) {
                p.append("(allow file-write* (subpath ").append(q(Path.of(dir))).append("))\n");
            }
            for (Path prot : allProtected(ws)) {
                p.append("(deny file-write* (subpath ").append(q(prot)).append("))\n");
            }
            p.append("(deny network*)\n");
            List<String> a = new ArrayList<>(List.of("sandbox-exec", "-p"));
            if (proxy != null && proxy.port() > 0) {
                p.append("(allow network-outbound (remote ip \"localhost:").append(proxy.port()).append("\"))\n");
                a.add(p.toString());
                a.add("env");
                String url = "http://127.0.0.1:" + proxy.port();
                for (String var : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
                    a.add(var + "=" + url);
                }
            } else {
                a.add(p.toString());
            }
            a.addAll(argv);
            return a;
        }

        private static String q(Path path) {
            return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
