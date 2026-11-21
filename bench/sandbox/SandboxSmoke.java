import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.BashTool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.CommandGuard;
import dev.wiles.myagent.tool.sandbox.Sandbox;
import dev.wiles.myagent.tool.sandbox.SandboxRunner;
import dev.wiles.myagent.tool.sandbox.SandboxSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux 上用真的 bubblewrap + 代理跑一遍：每一条都打印 PASS / FAIL。
 * 需要：bwrap、socat、curl、python3；容器要 --privileged（bwrap 要建命名空间）。
 * 外面在 127.0.0.1:8099 起一个 http 服务当「外网」。
 */
public class SandboxSmoke {
    static final ObjectMapper M = new ObjectMapper();
    static int failed = 0;
    static final List<String> asked = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        Path ws = Files.createTempDirectory("ws");
        new ProcessBuilder("git", "init", "-q", ws.toString()).inheritIO().start().waitFor();
        Path outside = Files.createTempDirectory("outside");
        ApprovalPolicy approval = new ApprovalPolicy() {
            public ApprovalVerdict decide(ApprovalRequest r) {
                asked.add(r.kind().id() + ":" + r.subject());
                return ApprovalVerdict.no("smoke 里一律不批");
            }
        };
        Sandbox sandbox = Sandbox.current();
        check("这台机器上沙箱可用", sandbox.unavailable() == null, String.valueOf(sandbox.unavailable()));

        SandboxSettings closed = new SandboxSettings(true, false, true, true, List.of(), List.of(), List.of(), false);
        BashTool bash = new BashTool(new Workspace(ws), new CommandGuard(), approval, new SandboxRunner(closed, sandbox, approval));

        String r = run(bash, "echo hi > inside.txt && cat inside.txt", false);
        check("工作区里能写", r.contains("hi") && Files.exists(ws.resolve("inside.txt")), r);

        // 用 python 写：命令文本里看不出写哪儿，命令守卫拦不住——这正是沙箱该兜住的
        r = run(bash, "python3 -c \"open('" + outside + "/escape.txt','w').write('x')\"; echo rc=$?", false);
        check("工作区外写不了（命令守卫看不出来的写法，靠沙箱拦）", !Files.exists(outside.resolve("escape.txt")) && r.contains("rc=1"), r);

        r = run(bash, "echo evil > .git/hooks/pre-commit; echo rc=$?", false);
        check(".git/hooks 只读", !Files.exists(ws.resolve(".git/hooks/pre-commit")), r);

        r = run(bash, "python3 -c \"open('/tmp/smoke-marker','w').write('s')\" && cat /tmp/smoke-marker", false);
        check("沙箱里的 /tmp 能写，而且和外面的 /tmp 是两个", r.contains("s") && !Files.exists(Path.of("/tmp/smoke-marker")), r);
        r = run(bash, "cat /tmp/smoke-marker", false);
        check("沙箱里的 /tmp 跨命令留着（上一条写的下一条读得到）", r.contains("s") && !r.contains("No such file"), r);

        asked.clear();
        r = run(bash, "curl -s -m 5 http://127.0.0.1:8099/ ; echo rc=$?", false);
        check("不在放行名单的主机：问了审批（不批）、连不上", !r.contains("SMOKE-OK") && asked.contains("network:127.0.0.1"), r + " asked=" + asked);

        r = run(bash, "curl -s -m 5 --noproxy '*' http://127.0.0.1:8099/ ; echo rc=$?", false);
        check("绕开代理直连：没有路", !r.contains("SMOKE-OK"), r);

        SandboxSettings allow = new SandboxSettings(true, false, true, true, List.of(), List.of("127.0.0.1"), List.of(), false);
        BashTool open = new BashTool(new Workspace(ws), new CommandGuard(), approval, new SandboxRunner(allow, sandbox, approval));
        asked.clear();
        r = run(open, "curl -s -m 5 http://127.0.0.1:8099/ ; echo rc=$?", false);
        check("放行名单里的主机：走代理连得上，不问", r.contains("SMOKE-OK") && asked.isEmpty(), r + " asked=" + asked);

        asked.clear();
        r = run(bash, "python3 -c \"open('" + outside + "/unsandboxed.txt','w').write('x')\"", true);
        check("沙箱外重跑要单独批（smoke 里不批 → 没跑）", asked.size() == 1 && !Files.exists(outside.resolve("unsandboxed.txt")), r + " asked=" + asked);

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String run(BashTool bash, String command, boolean out) {
        ToolResult r = bash.execute(M.createObjectNode().put("command", command).put("dangerously_disable_sandbox", out));
        return r.content().replace("\n", " ⏎ ");
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : "\n     " + detail));
        if (!ok) {
            failed++;
        }
    }
}
