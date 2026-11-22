package dev.wiles.myagent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱的出网代理（照 Claude Code）：沙箱里没有直通网络的路，唯一的出口是它。它按<b>主机名</b>判：
 * 拒绝名单里的一律拒；放行名单里的放；都不在的，strictAllowlist 下拒，否则问一次（{@link HostDecider}，
 * 背后是审批链——手动模式问人、auto 交给审查、dontAsk 拒），同一个主机不重复问。
 *
 * <p>只认 HTTP 代理协议：HTTPS 走 CONNECT（代理不解 TLS，只看主机名），明文 HTTP 走绝对地址。
 * 不认代理变量的工具（裸 ssh、多数数据库驱动）连放行的主机也连不上——和 Claude Code 一样。
 */
public final class NetworkProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkProxy.class);

    /** 两个名单都没有的主机：放不放行。 */
    @FunctionalInterface
    public interface HostDecider {
        boolean allow(String host);

        /**
         * 用户的 NETWORK 规则先说话（照 Claude Code：deny 永远先看，ask 一定问人），连放行名单上的主机也管。
         * deny → false；ask → 问过人的答案；没撞规则 → null，交给名单。
         */
        default Boolean ruled(String host) {
            return null;
        }
    }

    private final ServerSocketChannel server;
    private final Sandbox.Endpoint endpoint;
    private final SandboxSettings settings;
    private final HostDecider decider;
    private final Map<String, Boolean> decided = new ConcurrentHashMap<>();
    private final Path socketFile;

    private NetworkProxy(ServerSocketChannel server, Sandbox.Endpoint endpoint, Path socketFile,
                         SandboxSettings settings, HostDecider decider) {
        this.server = server;
        this.endpoint = endpoint;
        this.socketFile = socketFile;
        this.settings = settings;
        this.decider = decider;
        Thread t = new Thread(this::acceptLoop, "sandbox-proxy");
        t.setDaemon(true);
        t.start();
    }

    /** Linux：听一个 Unix socket（沙箱里的 socat 连它）。 */
    public static NetworkProxy unix(Path socket, SandboxSettings settings, HostDecider decider) throws IOException {
        Files.createDirectories(socket.getParent());
        Files.deleteIfExists(socket);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return new NetworkProxy(server, Sandbox.Endpoint.unix(socket), socket, settings, decider);
    }

    /** macOS：听本机一个端口（Seatbelt 只放行连它）。 */
    public static NetworkProxy tcp(SandboxSettings settings, HostDecider decider) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        return new NetworkProxy(server, Sandbox.Endpoint.tcp(port), null, settings, decider);
    }

    public Sandbox.Endpoint endpoint() {
        return endpoint;
    }

    /** {@code *.example.com} 连主域名一起罩住；{@code *} 罩所有；大小写不敏感。 */
    static boolean matches(String pattern, String host) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        if (p.equals("*") || p.equals(h)) {
            return true;
        }
        if (p.startsWith("*.")) {
            String domain = p.substring(2);
            return h.equals(domain) || h.endsWith("." + domain);
        }
        return false;
    }

    /** 规则的结论也按主机记住：ask 规则一条命令里只问一次。 */
    private final Map<String, Optional<Boolean>> ruledHosts = new ConcurrentHashMap<>();

    private Boolean rulesFor(String host) {
        return ruledHosts.computeIfAbsent(host.toLowerCase(Locale.ROOT), h -> {
            try {
                return Optional.ofNullable(decider.ruled(h));
            } catch (RuntimeException e) {
                log.warn("对 {} 查规则时出错，按拒绝处理: {}", h, e.getMessage());
                return Optional.of(false);
            }
        }).orElse(null);
    }

    /** 这个主机放不放：拒绝名单 → 用户的 deny / ask 规则 → 放行名单 → strict 拒 → 问一次（记住答案）。 */
    boolean allowed(String host) {
        if (settings.deniedDomains().stream().anyMatch(p -> matches(p, host))) {
            return false;
        }
        Boolean ruled = rulesFor(host);
        if (ruled != null) {
            return ruled;
        }
        if (settings.allowedDomains().stream().anyMatch(p -> matches(p, host))) {
            return true;
        }
        if (settings.strictAllowlist()) {
            return false;
        }
        return decided.computeIfAbsent(host.toLowerCase(Locale.ROOT), h -> {
            try {
                return decider.allow(h);
            } catch (RuntimeException e) {
                log.warn("问 {} 能不能连时出错，按拒绝处理: {}", h, e.getMessage());
                return false;
            }
        });
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                SocketChannel client = server.accept();
                Thread t = new Thread(() -> handle(client), "sandbox-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;                                 // 关了
            }
        }
    }

    private void handle(SocketChannel client) {
        try (client) {
            // 不用 Channels.newInputStream / newOutputStream：它们读写同一个阻塞 channel 时抢同一把 blockingLock，
            // 一个线程阻塞在读、拿着锁，另一个线程的写就永远进不去——双向转发时必然死锁
            InputStream in = inputOf(client);
            OutputStream out = outputOf(client);
            byte[] head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int lineEnd = text.indexOf("\r\n");
            String[] first = text.substring(0, lineEnd < 0 ? text.length() : lineEnd).split(" ");
            if (first.length < 3) {
                refuse(out, "?", "看不懂的请求");
                return;
            }
            String method = first[0];
            String target = first[1];
            String host;
            int port;
            byte[] forward;
            if ("CONNECT".equalsIgnoreCase(method)) {
                int colon = target.lastIndexOf(':');
                host = colon < 0 ? target : target.substring(0, colon);
                port = colon < 0 ? 443 : Integer.parseInt(target.substring(colon + 1));
                forward = null;
            } else if (target.startsWith("http://")) {
                String rest = target.substring("http://".length());
                int slash = rest.indexOf('/');
                String authority = slash < 0 ? rest : rest.substring(0, slash);
                String path = slash < 0 ? "/" : rest.substring(slash);
                int colon = authority.lastIndexOf(':');
                host = colon < 0 ? authority : authority.substring(0, colon);
                port = colon < 0 ? 80 : Integer.parseInt(authority.substring(colon + 1));
                // 绝对地址改写成路径：上游不是代理，只认 GET /path
                String rewritten = method + " " + path + " " + first[2] + text.substring(lineEnd);
                forward = rewritten.getBytes(StandardCharsets.ISO_8859_1);
            } else {
                refuse(out, "?", "只认 HTTP 代理请求（CONNECT 或绝对地址）");
                return;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (!allowed(host)) {
                refuse(out, host, "不在放行的域名里");
                return;
            }
            try (Socket upstream = new Socket(host, port)) {
                if (forward == null) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    byte[] extra = rest(head, text);
                    if (extra.length > 0) {
                        upstream.getOutputStream().write(extra);
                    }
                } else {
                    upstream.getOutputStream().write(forward);
                }
                upstream.getOutputStream().flush();
                Thread back = new Thread(() -> pipe(upstreamIn(upstream), out), "sandbox-proxy-back");
                back.setDaemon(true);
                back.start();
                pipe(in, upstream.getOutputStream());
                back.join(2000);
            }
        } catch (Exception e) {
            log.debug("代理连接结束: {}", e.getMessage());
        }
    }

    private static InputStream inputOf(SocketChannel ch) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return ch.read(java.nio.ByteBuffer.wrap(b, off, len));
            }
        };
    }

    private static OutputStream outputOf(SocketChannel ch) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b, off, len);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
        };
    }

    private static InputStream upstreamIn(Socket s) {
        try {
            return s.getInputStream();
        } catch (IOException e) {
            return InputStream.nullInputStream();
        }
    }

    /** 读到请求头结束（\r\n\r\n）为止，最多 64KB。头后面跟着的字节（CONNECT 之后客户端抢先发的）留在 rest 里。 */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (buf.size() < 65536) {
            int n = in.read(chunk);
            if (n < 0) {
                return buf.size() == 0 ? null : buf.toByteArray();
            }
            buf.write(chunk, 0, n);
            if (buf.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
                return buf.toByteArray();
            }
        }
        return buf.toByteArray();
    }

    private static byte[] rest(byte[] head, String text) {
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            return new byte[0];
        }
        int from = end + 4;
        byte[] out = new byte[head.length - from];
        System.arraycopy(head, from, out, 0, out.length);
        return out;
    }

    private static void refuse(OutputStream out, String host, String why) throws IOException {
        String body = "myagent 沙箱拦下了到 " + host + " 的连接：" + why + "。\n";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // 一头断了
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 已经关了
        }
        if (socketFile != null) {
            try {
                Files.deleteIfExists(socketFile);
            } catch (IOException ignored) {
                // 删不掉就留着，临时目录会收
            }
        }
    }
}
