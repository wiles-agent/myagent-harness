package dev.wiles.myagent.tool.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---------- 设置（照 Claude Code 的 sandbox.* 键） ----------

    @Test
    @DisplayName("默认关（照 Claude Code）；sandbox 段照 Claude Code 的键名读")
    void settingsParse() throws Exception {
        assertFalse(SandboxSettings.parse(null).enabled());
        SandboxSettings s = SandboxSettings.parse(M.readTree("""
                {"enabled": true, "failIfUnavailable": true, "allowUnsandboxedCommands": false,
                 "filesystem": {"allowWrite": ["/tmp/build"]},
                 "network": {"allowedDomains": ["github.com", "*.npmjs.org"], "deniedDomains": ["evil.com"],
                             "strictAllowlist": true}}"""));
        assertTrue(s.enabled());
        assertTrue(s.failIfUnavailable());
        assertFalse(s.allowUnsandboxedCommands());
        assertTrue(s.autoAllow(), "不写就是自动放行沙箱里的命令（照 Claude Code 的默认）");
        assertEquals(List.of("/tmp/build"), s.allowWrite());
        assertEquals(List.of("github.com", "*.npmjs.org"), s.allowedDomains());
        assertEquals(List.of("evil.com"), s.deniedDomains());
        assertTrue(s.strictAllowlist());
    }

    @Test
    @DisplayName("只读家目录的 settings.json；MYAGENT_SANDBOX=on/off 压过它（评测、CI 要能一键开关）")
    void settingsLoad() throws Exception {
        Path home = tmp.resolve("settings.json");
        Files.writeString(home, "{\"sandbox\": {\"enabled\": true}}");
        assertTrue(SandboxSettings.load(home, k -> null).enabled());
        assertFalse(SandboxSettings.load(home, Map.of("MYAGENT_SANDBOX", "off")::get).enabled());
        assertTrue(SandboxSettings.load(tmp.resolve("nope.json"), Map.of("MYAGENT_SANDBOX", "on")::get).enabled());
        Files.writeString(home, "{坏的");
        assertFalse(SandboxSettings.load(home, k -> null).enabled(), "读坏了按没开算，不能崩");
    }

    @Test
    @DisplayName("failIfUnavailable（照 Claude Code）：开了、要求必须有、平台又不支持——启动就失败，说清为什么")
    void failIfUnavailable() throws Exception {
        Sandbox windows = Sandbox.forPlatform("Windows 10", c -> true);
        Sandbox linux = Sandbox.forPlatform("Linux", c -> true);
        SandboxSettings strict = SandboxSettings.parse(M.readTree("{\"enabled\":true,\"failIfUnavailable\":true}"));
        SandboxSettings lax = SandboxSettings.parse(M.readTree("{\"enabled\":true}"));
        assertNotNull(strict.startupProblem(windows));
        assertTrue(strict.startupProblem(windows).contains("WSL2"), strict.startupProblem(windows));
        assertNull(strict.startupProblem(linux));
        assertNull(lax.startupProblem(windows), "没要求必须有：不进沙箱照跑（Claude Code 的默认）");
        assertNull(SandboxSettings.OFF.startupProblem(windows));
    }

    // ---------- 平台 ----------

    @Test
    @DisplayName("Linux / WSL2 用 bubblewrap，macOS 用 Seatbelt，原生 Windows 不支持（照 Claude Code）")
    void platformSelection() {
        assertEquals("bubblewrap", Sandbox.forPlatform("Linux", cmd -> true).name());
        assertEquals("seatbelt", Sandbox.forPlatform("Mac OS X", cmd -> true).name());
        Sandbox win = Sandbox.forPlatform("Windows 10", cmd -> true);
        assertNotNull(win.unavailable());
        assertTrue(win.unavailable().contains("WSL2"), "要告诉用户怎么才能用上: " + win.unavailable());
        assertNotNull(Sandbox.forPlatform("Linux", cmd -> !cmd.equals("bwrap")).unavailable(), "没装 bwrap 就不可用");
        assertNull(Sandbox.forPlatform("Linux", cmd -> true).unavailable());
    }

    // ---------- bubblewrap ----------

    @Test
    @DisplayName("bubblewrap：整机只读、工作区和临时目录可写、受保护路径只读、单独的网络命名空间")
    void bubblewrapArgs() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".git/hooks"));
        Files.createDirectories(ws.resolve(".myagent"));
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        SandboxSettings s = SandboxSettings.parse(M.readTree("{\"enabled\":true,\"filesystem\":{\"allowWrite\":[\"/opt/cache\"]}}"));
        List<String> argv = Sandbox.forPlatform("Linux", c -> true)
                .wrap(List.of("sh", "/tmp/cmd.sh"), ws, scratch, null, s);
        String line = String.join(" ", argv);
        assertEquals("bwrap", argv.get(0));
        assertTrue(line.contains("--ro-bind / /"), line);
        assertTrue(line.contains("--bind " + ws + " " + ws), line);
        assertTrue(line.contains("--bind " + scratch + " /tmp"), "临时目录可写: " + line);
        assertTrue(line.contains("--bind-try /opt/cache /opt/cache"), "allowWrite 的目录可写（不存在就跳过，不让 bwrap 起不来）: " + line);
        assertTrue(line.contains("--ro-bind " + ws.resolve(".git/hooks") + " " + ws.resolve(".git/hooks")),
                "受保护路径在可写目录里也只读: " + line);
        assertTrue(line.contains("--ro-bind " + ws.resolve(".myagent") + " " + ws.resolve(".myagent")), line);
        assertTrue(line.contains("--unshare-net"), "没有直通网络的路: " + line);
        assertTrue(line.contains("--die-with-parent"), line);
        assertTrue(line.endsWith("-- sh /tmp/cmd.sh"), "原命令原样放在最后: " + line);
        assertTrue(line.indexOf("--bind " + ws) < line.indexOf("--ro-bind " + ws.resolve(".git/hooks")),
                "受保护路径要在工作区之后挂，才盖得住: " + line);
    }

    @Test
    @DisplayName("bubblewrap 接网络代理：socket 所在目录挂进去，沙箱里用 socat 在 127.0.0.1 上转给它，代理变量指过去")
    void bubblewrapWithProxy() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path sock = tmp.resolve("p/proxy.sock");
        List<String> argv = Sandbox.forPlatform("Linux", c -> true)
                .wrap(List.of("sh", "/tmp/cmd.sh"), ws, tmp, Sandbox.Endpoint.unix(sock),
                        SandboxSettings.parse(M.readTree("{\"enabled\":true}")));
        String line = String.join(" ", argv);
        assertTrue(line.contains("--bind " + sock.getParent() + " " + sock.getParent()), line);
        assertTrue(line.contains("socat"), line);
        assertTrue(line.contains("UNIX-CONNECT:" + sock), line);
        assertTrue(line.contains("--setenv HTTPS_PROXY http://127.0.0.1:"), line);
        assertTrue(line.contains("--setenv https_proxy http://127.0.0.1:"), "小写的也要设，很多工具只认小写: " + line);
        assertTrue(line.contains("--unshare-net"), "有代理也照样隔开网络，只留代理这一条路: " + line);
    }

    // ---------- Seatbelt ----------

    @Test
    @DisplayName("Seatbelt：默认拒写，只放行工作区和临时目录；受保护路径照样拒；网络只许连代理端口")
    void seatbeltProfile() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".git/hooks"));
        List<String> argv = Sandbox.forPlatform("Mac OS X", c -> true)
                .wrap(List.of("sh", "/tmp/cmd.sh"), ws, tmp, null, SandboxSettings.parse(M.readTree("{\"enabled\":true}")));
        assertEquals("sandbox-exec", argv.get(0));
        String profile = argv.get(2);
        assertTrue(profile.contains("(deny file-write*)"), profile);
        // 配置文件里的路径要转义反斜杠（这台测试机是 Windows 路径；macOS 上没有反斜杠，转义不改变它）
        String wsQ = ws.toString().replace("\\", "\\\\");
        String hooksQ = ws.resolve(".git/hooks").toString().replace("\\", "\\\\");
        assertTrue(profile.contains("(allow file-write* (subpath \"" + wsQ + "\"))"), profile);
        assertTrue(profile.contains("(deny file-write* (subpath \"" + hooksQ + "\"))"), profile);
        assertTrue(profile.contains("(deny network*)"), profile);
        assertEquals(List.of("sh", "/tmp/cmd.sh"), argv.subList(argv.size() - 2, argv.size()));
    }

    // ---------- 本来不存在的受保护路径 ----------

    @Test
    @DisplayName("bubblewrap：不存在的受保护路径拿 /dev/null 占住——沙箱里建不出 .myagent / .mcp.json，也写不进 .git/hooks")
    void bubblewrapMasksMissingProtectedPaths() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".git"));       // 有仓库、没 hooks 目录
        Sandbox bwrap = Sandbox.forPlatform("Linux", c -> true);
        String line = String.join(" ", bwrap.wrap(List.of("sh", "/tmp/cmd.sh"), ws, tmp, null,
                SandboxSettings.parse(M.readTree("{\"enabled\":true}"))));
        for (String missing : List.of(".myagent", ".mcp.json", ".git/hooks", ".git/config")) {
            assertTrue(line.contains("--ro-bind /dev/null " + ws.resolve(missing)), missing + ": " + line);
        }
        assertEquals(bwrap.masked(ws).size(), countOf(line, "--ro-bind /dev/null "),
                "占位的就是 masked() 报的那些，跑完要收掉 bwrap 留下的挂载点");
        assertTrue(line.indexOf("--bind " + ws) < line.indexOf("--ro-bind /dev/null"), "要挂在工作区之后: " + line);
    }

    @Test
    @DisplayName("bubblewrap：还不是 git 仓库时不占 .git——不然沙箱里 git init 都跑不了")
    void bubblewrapLeavesGitInitAlone() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Sandbox bwrap = Sandbox.forPlatform("Linux", c -> true);
        String line = String.join(" ", bwrap.wrap(List.of("sh", "/tmp/cmd.sh"), ws, tmp, null,
                SandboxSettings.parse(M.readTree("{\"enabled\":true}"))));
        assertFalse(line.contains(ws.resolve(".git") + " "), line);
        assertFalse(line.contains(ws.resolve(".git/hooks").toString()), line);
        assertTrue(line.contains("--ro-bind /dev/null " + ws.resolve(".myagent")), line);
    }

    @Test
    @DisplayName("Seatbelt：拒写规则对不存在的路径本来就管用，受保护路径不管在不在都写进去")
    void seatbeltDeniesMissingProtectedPaths() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        String profile = Sandbox.forPlatform("Mac OS X", c -> true)
                .wrap(List.of("sh"), ws, tmp, null, SandboxSettings.parse(M.readTree("{\"enabled\":true}"))).get(2);
        String q = ws.resolve(".myagent").toString().replace("\\", "\\\\");
        assertTrue(profile.contains("(deny file-write* (subpath \"" + q + "\"))"), profile);
    }

    private static int countOf(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
