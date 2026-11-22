package dev.wiles.myagent.tool.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProxyTest {

    @TempDir
    Path tmp;

    /** 「外面的服务」：收到什么记下来，回一句 hello。 */
    private ServerSocket upstream;
    private final List<String> upstreamSaw = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!upstream.isClosed()) {
                try (Socket s = upstream.accept()) {
                    byte[] buf = new byte[4096];
                    int n = s.getInputStream().read(buf);
                    upstreamSaw.add(n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "");
                    s.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // 关了
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void stop() throws Exception {
        upstream.close();
    }

    private SandboxSettings settings(String allowed, String denied, boolean strict) {
        return new SandboxSettings(true, false, true, true, List.of(),
                allowed == null ? List.of() : List.of(allowed), denied == null ? List.of() : List.of(denied), strict);
    }

    private NetworkProxy proxy(SandboxSettings s, boolean answer) throws Exception {
        return NetworkProxy.unix(tmp.resolve("proxy.sock"), s, host -> {
            asked.add(host);
            return answer;
        });
    }

    /** 像沙箱里的 socat 那样连上代理的 socket，发一段请求，读回全部。 */
    private String send(NetworkProxy proxy, String request) throws Exception {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(proxy.endpoint().socket()));
            OutputStream out = Channels.newOutputStream(ch);
            InputStream in = Channels.newInputStream(ch);
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                got.write(buf, 0, n);
                if (got.toString(StandardCharsets.UTF_8).contains("hello")) {
                    break;                          // 拒绝时代理会关连接，读到流结束自然退出
                }
            }
            return got.toString(StandardCharsets.UTF_8);
        }
    }

    private String connect(int port) {
        return "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\nping";
    }

    @Test
    @DisplayName("放行名单里的：CONNECT 打通，字节原样来回（HTTPS 就是这么过的，代理不解 TLS）")
    void allowedHostTunnels() throws Exception {
        try (NetworkProxy p = proxy(settings("127.0.0.1", null, false), false)) {
            String got = send(p, connect(upstream.getLocalPort()));
            assertTrue(got.startsWith("HTTP/1.1 200"), got);
            assertTrue(got.contains("hello"), got);
            assertTrue(asked.isEmpty(), "名单里有的不用问");
        }
    }

    @Test
    @DisplayName("拒绝名单里的：哪种模式下都拒，连都不连")
    void deniedHostIsRefused() throws Exception {
        try (NetworkProxy p = proxy(settings("*", "127.0.0.1", false), true)) {
            String got = send(p, connect(upstream.getLocalPort()));
            assertTrue(got.startsWith("HTTP/1.1 403"), got);
            assertTrue(got.contains("127.0.0.1"), "要说清拦的是哪个主机: " + got);
            assertTrue(upstreamSaw.isEmpty());
        }
    }

    @Test
    @DisplayName("两个名单都没有：问一次（审批链决定：问人 / 审查 / dontAsk 拒），同一个主机不重复问")
    void unknownHostIsAskedOnce() throws Exception {
        try (NetworkProxy p = proxy(settings(null, null, false), false)) {
            assertTrue(send(p, connect(upstream.getLocalPort())).startsWith("HTTP/1.1 403"));
            assertTrue(send(p, connect(upstream.getLocalPort())).startsWith("HTTP/1.1 403"));
            assertEquals(List.of("127.0.0.1"), asked);
        }
        asked.clear();
        try (NetworkProxy p = proxy(settings(null, null, false), true)) {
            assertTrue(send(p, connect(upstream.getLocalPort())).contains("hello"));
        }
    }

    @Test
    @DisplayName("strictAllowlist：不在放行名单里的直接拒，不问")
    void strictAllowlistNeverAsks() throws Exception {
        try (NetworkProxy p = proxy(settings(null, null, true), true)) {
            assertTrue(send(p, connect(upstream.getLocalPort())).startsWith("HTTP/1.1 403"));
            assertTrue(asked.isEmpty());
        }
    }

    @Test
    @DisplayName("明文 HTTP 也走代理：绝对地址改写成路径再转出去（http_proxy 下 curl http://… 就是这么发的）")
    void plainHttpIsForwarded() throws Exception {
        try (NetworkProxy p = proxy(settings("127.0.0.1", null, false), false)) {
            int port = upstream.getLocalPort();
            String got = send(p, "GET http://127.0.0.1:" + port + "/report?x=1 HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
            assertTrue(got.contains("hello"), got);
            assertTrue(upstreamSaw.get(0).startsWith("GET /report?x=1 HTTP/1.1"), upstreamSaw.get(0));
        }
    }

    @Test
    @DisplayName("*.example.com 连主域名一起罩住；大小写不敏感")
    void domainPatterns() {
        assertTrue(NetworkProxy.matches("*.github.com", "api.github.com"));
        assertTrue(NetworkProxy.matches("*.github.com", "GitHub.com"));
        assertFalse(NetworkProxy.matches("*.github.com", "evilgithub.com"));
        assertTrue(NetworkProxy.matches("pypi.org", "PYPI.org"));
        assertFalse(NetworkProxy.matches("pypi.org", "files.pypi.org"));
    }

    @Test
    @DisplayName("NETWORK 的 deny 规则压过放行名单（照 Claude Code：deny 永远先说话）")
    void ruleDenyBeatsAllowedDomains() throws Exception {
        NetworkProxy.HostDecider decider = new NetworkProxy.HostDecider() {
            @Override
            public boolean allow(String host) {
                asked.add(host);
                return true;
            }

            @Override
            public Boolean ruled(String host) {
                return Boolean.FALSE;
            }
        };
        try (NetworkProxy p = NetworkProxy.unix(tmp.resolve("proxy.sock"), settings("127.0.0.1", null, false), decider)) {
            assertTrue(send(p, connect(upstream.getLocalPort())).startsWith("HTTP/1.1 403"));
            assertTrue(upstreamSaw.isEmpty());
            assertTrue(asked.isEmpty());
        }
    }
}
