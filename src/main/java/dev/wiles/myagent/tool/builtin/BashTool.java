package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import java.io.IOException;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.CommandGuard;
import dev.wiles.myagent.tool.safety.CommandRisk;

/**
 * 执行 shell 命令。**五个工具里威力最大、也最危险的一个。**
 *
 * 危险在于它绕过了 Workspace 沙箱：cat ../../../etc/passwd 照样能跑。
 * 一旦开放这个工具，前面辛苦做的路径校验就只剩「防手滑」的作用了。
 *
 * 所以这里叠了四层防护，层层都不完美，合起来才够用：
 *   1. 限定工作目录、超时、输出截断 —— 防手滑和防失控
 *   2. CommandGuard 静态评估 —— 挡住明显危险（但挡不住蓄意绕过）
 *   3. ApprovalPolicy 人工确认 —— 真正的防线，重要动作总有人过目
 *   4. 被拦下来时返回 error 而不是抛异常 —— 模型看得懂，会换个做法
 */
public final class BashTool implements Tool {

    /** 默认超时。模型很容易生成一个卡住的命令（比如误开交互式程序），不设超时 agent 就永远挂在那。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    /** 输出截断上限，理由同 ReadFileTool：工具输出会变成 token。 */
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final Workspace workspace;
    private final CommandGuard guard;
    private final ApprovalPolicy approval;
    private final dev.wiles.myagent.tool.sandbox.SandboxRunner sandbox;

    /** 不指定审批策略时一律拒绝：非交互环境没人能点头，默认就该是最安全的那档。 */
    public BashTool(Workspace workspace) {
        this(workspace, new CommandGuard(), ApprovalPolicy.denyAll());
    }

    public BashTool(Workspace workspace, CommandGuard guard, ApprovalPolicy approval) {
        this(workspace, guard, approval, dev.wiles.myagent.tool.sandbox.SandboxRunner.off());
    }

    /** @param sandbox 沙箱（照 Claude Code）；没开或这个平台用不了时命令照常跑、照常审批 */
    public BashTool(Workspace workspace, CommandGuard guard, ApprovalPolicy approval,
                    dev.wiles.myagent.tool.sandbox.SandboxRunner sandbox) {
        this.workspace = workspace;
        this.guard = guard;
        this.approval = approval;
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作区根目录下执行一条 shell 命令，返回标准输出和标准错误的合并结果。"
                + "适合运行构建、测试、git 等命令。"
                + "禁止用它查看目录或读写文件——列目录必须用 list_dir，读文件必须用 read_file，"
                + "改已有文件用 edit_file，新建文件用 write_file。"
                + "不要执行需要交互输入的命令。"
                + "删除文件、访问网络、安装依赖等操作会先征求用户同意，可能被拒绝。"
                + (sandbox.active() ? SANDBOX_NOTE : "");
    }

    private static final String SANDBOX_NOTE = "\n命令在沙箱里跑：只能写工作区和临时目录，"
            + "受保护的路径（.git/hooks、.myagent 等）只读；联网只能走代理，不在放行名单里的域名会被拦或要批。"
            + "沙箱里的命令一般不用等批准。确实因为沙箱的限制跑不通（不是命令本身的错），"
            + "可以带 dangerously_disable_sandbox: true 在沙箱外重跑——那要用户单独批准，别拿它绕开该有的限制。";

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "要执行的 shell 命令，例如 mvn -q test"
                    },
                    "timeout_seconds": {
                      "type": "integer",
                      "description": "超时秒数，默认 30，最大 300"
                    },
                    "dangerously_disable_sandbox": {
                      "type": "boolean",
                      "description": "只在沙箱开着时有用：在沙箱外跑这条命令，要用户单独批准"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    /**
     * 沙箱里的命令怎么批（照 Claude Code 的 auto-allow）：不问人，但 deny 规则照样拦；
     * auto 下照样过审查（auto-allow 不跳过它）；设置里关了 autoAllow 就照常走审批。
     *
     * @return 拦下时的错误；放行返回 null
     */
    private ToolResult approveSandboxed(String command, CommandRisk risk) {
        if (!risk.needsApproval()) {
            return null;
        }
        ApprovalRequest request = ApprovalRequest.bash(command, risk.reason());
        boolean autoMode = dev.wiles.myagent.tool.safety.ApprovalScope.autoReviewer() != null;
        ApprovalVerdict verdict = sandbox.settings().autoAllow() && !autoMode
                ? approval.denyOnly(request)
                : approval.decide(request);
        if (verdict == null || verdict.approved()) {
            return null;
        }
        return ToolResult.error(verdict.deniedMessage(
                "用户拒绝执行这条命令（" + risk.reason() + "）。请换一种做法，或者先向用户说明为什么需要它。"));
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String command = Args.requireString(args, "command");
        int timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS,
                Args.optInt(args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS)));

        // 先过安全闸：被拦下来要把理由讲清楚，模型才能换个做法而不是原样重试
        CommandRisk risk = guard.assess(command);
        if (risk.isForbidden()) {
            return ToolResult.error("这条命令被安全策略拒绝执行。原因: " + risk.reason()
                    + "。请改用其它方式完成目标，不要重试同样的命令。");
        }
        // 沙箱（照 Claude Code）：开着且用得上时命令进沙箱；要在沙箱外跑得单独批，设置能把这条路关掉
        boolean wantsOut = Args.optBoolean(args, "dangerously_disable_sandbox", false)
                && sandbox.settings().allowUnsandboxedCommands();
        boolean sandboxed = sandbox.active() && !wantsOut;
        if (sandboxed) {
            ToolResult refused = approveSandboxed(command, risk);
            if (refused != null) {
                return refused;
            }
        } else if (sandbox.active()) {
            // 沙箱外重跑：哪怕是 echo 也问——问的是「出沙箱」这件事本身
            String reason = "要在沙箱外执行（dangerously_disable_sandbox）"
                    + (risk.needsApproval() ? "；" + risk.reason() : "");
            ApprovalVerdict verdict = approval.decide(ApprovalRequest.bash(command, reason));
            if (!verdict.approved()) {
                return ToolResult.error(verdict.deniedMessage("用户没批准在沙箱外执行这条命令。"
                        + "在沙箱里换一种做法；确实做不到就说明原因，交给用户。"));
            }
        } else if (risk.needsApproval()) {
            ApprovalVerdict verdict = approval.decide(ApprovalRequest.bash(command, risk.reason()));
            if (!verdict.approved()) {
                return ToolResult.error(verdict.deniedMessage(
                        "用户拒绝执行这条命令（" + risk.reason() + "）。"
                                + "请换一种做法，或者先向用户说明为什么需要它。"));
            }
        }

        long startedAt = System.currentTimeMillis();
        ProcessRunner.Outcome out;
        try {
            out = sandboxed
                    ? ProcessRunner.runShell(command, argv -> sandbox.run(argv, workspace.root(),
                            wrapped -> ProcessRunner.run(wrapped, workspace.root(), timeout)))
                    : ProcessRunner.runShell(command, workspace.root(), timeout);
        } catch (IOException e) {
            return ToolResult.error("无法启动命令: " + e.getMessage());
        }
        if (out.interrupted()) {
            return ToolResult.error(ProcessRunner.stoppedMessage(
                    System.currentTimeMillis() - startedAt, out.output(), MAX_OUTPUT_CHARS));
        }
        if (!out.finished()) {
            return ToolResult.error("命令执行超时（超过 " + timeout + " 秒），已强制终止。"
                    + "已产生的输出:\n" + ProcessRunner.truncate(out.output(), MAX_OUTPUT_CHARS));
        }
        String output = ProcessRunner.truncate(out.output(), MAX_OUTPUT_CHARS);
        int exitCode = out.exitCode();

        // 退出码非 0 依然算工具执行成功：命令跑完了，只是结果失败（比如测试没过）。
        // 这和「工具本身没能执行」是两回事，模型需要看到完整输出去分析原因。
        StringBuilder sb = new StringBuilder();
        if (!output.isBlank()) {
            sb.append(output);
            if (!output.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("(没有输出)\n");
        }
        sb.append("退出码: ").append(exitCode);
        return ToolResult.ok(sb.toString());
    }


}
