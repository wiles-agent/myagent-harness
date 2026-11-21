package dev.wiles.myagent.tool.safety;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * shell 命令风险评估。
 *
 * <h3>先说清楚它做不到什么</h3>
 * shell 太灵活，静态检查**不可能**做到万无一失。
 * {@code eval $(echo cm0gLXJmIC8K | base64 -d)} 这种就绕过去了，
 * 变量拼接、别名、脚本文件同理。所以这个类的定位是
 * <b>挡住明显危险和手滑，不是对抗蓄意攻击</b>。
 * 真正的防线是后面那道人工确认——重要动作总有人过目。
 *
 * <h3>三档判定</h3>
 * <ul>
 *   <li>{@link RiskLevel#FORBIDDEN} 直接拒绝，用户同意也不执行：删根目录、提权、
 *       格式化、关机、读私钥、管道到 shell</li>
 *   <li>{@link RiskLevel#NEEDS_APPROVAL} 要人点头：删改文件、联网、装包、改权限</li>
 *   <li>{@link RiskLevel#SAFE} 直接放行：只读查看、编译测试等</li>
 * </ul>
 * <b>未知命令一律归入 NEEDS_APPROVAL</b>——白名单没列到的东西，宁可多问一句。
 *
 * <h3>为什么要按分隔符拆开逐段检查</h3>
 * {@code ls && rm -rf ~} 只看第一个词是 ls 就放行，等于没防。
 * 所以 {@code ; && || |} 和子命令 {@code $() ``} 都要当作分界，每一段单独判，取最严的那档。
 */
public final class CommandGuard {

    /**
     * 这些命令<b>规划阶段就该避开</b>，别等执行时才被拦。
     *
     * <p>排过序，因为它会被拼进规划提示词——顺序不稳定的话，
     * 同一个工作区每次规划的提示词都不一样，缓存命不中，出了问题也不好复现。
     *
     * @return 禁止命令的名字，已排序
     */
    public static List<String> forbiddenCommands() {
        return FORBIDDEN_COMMANDS.stream().sorted().toList();
    }

    /** 无论如何都不执行。 */
    private static final Set<String> FORBIDDEN_COMMANDS = Set.of(
            "sudo", "su", "doas",                                   // 提权
            "mkfs", "fdisk", "diskutil", "parted", "shred", "srm",   // 抹盘
            "shutdown", "reboot", "halt", "poweroff", "init",        // 关机
            "launchctl", "systemctl", "service", "crontab",          // 系统服务与定时任务
            "useradd", "userdel", "usermod", "passwd", "visudo", "chpasswd");

    /** 要人点头才执行。 */
    private static final Set<String> APPROVAL_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "dd", "truncate", "tee", "ln",       // 能毁掉已有文件
            "chmod", "chown", "chgrp",                                // 改权限
            "curl", "wget", "nc", "ncat", "telnet", "ssh", "scp",     // 出网
            "sftp", "rsync", "ftp",
            "kill", "killall", "pkill",                               // 动别的进程
            "npm", "pnpm", "yarn", "pip", "pip3", "brew",             // 装包会执行第三方脚本
            "apt", "apt-get", "yum", "dnf", "gem", "composer",
            "docker", "podman", "kubectl",                            // 容器与集群
            "eval", "exec", "source", "xargs",                        // 能把任意字符串变成命令
            "osascript", "open", "defaults");

    /** 只读或影响限于工作区，直接放行。 */
    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "ag",
            "find", "fd", "wc", "echo", "printf", "pwd", "cd", "which", "whereis",
            "type", "file", "stat", "du", "df", "tree", "realpath", "readlink",
            "diff", "sort", "uniq", "cut", "tr", "jq", "basename", "dirname",
            "date", "sleep", "true", "false", "test", "uname", "whoami",
            "hostname", "seq", "yes", "touch", "mkdir", "cp", "awk", "sed",
            "java", "javac", "jar", "mvn", "gradle", "node", "npx", "deno",
            "python", "python3", "go", "rustc", "cargo", "make", "cmake", "dotnet");

    /** 带后缀的变体也算，比如 mkfs.ext4、mkfs.xfs。 */
    private static final Set<String> FORBIDDEN_PREFIXES = Set.of("mkfs", "newfs");

    /** 包管理器里这些子命令只是跑脚本或查信息，不装东西。 */
    private static final Set<String> PACKAGE_MANAGERS = Set.of("npm", "pnpm", "yarn");
    private static final Set<String> SAFE_PACKAGE_SUBCOMMANDS = Set.of(
            "test", "run", "start", "ls", "list", "outdated", "view", "why", "version");

    /**
     * 怎么写都只读的 git 子命令。remote / branch / reflog / stash / worktree / config 不在这儿：
     * 它们有只读的形式也有改东西的形式（remote set-url、branch -D、reflog expire），见 {@link #gitRisk}。
     */
    private static final Set<String> READ_ONLY_GIT = Set.of(
            "status", "log", "diff", "show", "ls-files", "rev-parse", "describe", "blame", "shortlog",
            "cat-file", "ls-tree", "rev-list", "for-each-ref", "merge-base", "name-rev", "show-ref",
            "grep", "count-objects", "check-ignore", "help", "version");

    private static final Set<String> BRANCH_MUTATING = Set.of(
            "-d", "-D", "--delete", "-m", "-M", "--move", "-c", "-C", "--copy", "-f", "--force",
            "-u", "--set-upstream-to", "--unset-upstream", "--edit-description");

    /** 带上这些，git branch 后面的名字是在「查」而不是「建」。 */
    private static final Set<String> BRANCH_QUERY = Set.of(
            "--list", "-l", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at");

    private static final Set<String> CONFIG_READ = Set.of(
            "--get", "--get-all", "--get-regexp", "--get-urlmatch", "--list", "-l");

    private static final Set<String> CONFIG_WRITE = Set.of(
            "--unset", "--unset-all", "--add", "--replace-all", "--rename-section", "--remove-section",
            "-e", "--edit");

    /** 出现这些片段就认定在碰凭据，一律拒绝——agent 没有任何理由读它们。 */
    private static final List<String> SECRET_PATHS = List.of(
            "/.ssh", "id_rsa", "id_ed25519", "id_dsa", "/.aws", "/.gnupg",
            "/.kube/config", "/etc/shadow", "/etc/sudoers", "/etc/passwd",
            ".pem", ".p12", "keychain");

    /**
     * 这个路径是不是密钥 / 凭据文件。是就返回命中的那个片段，不是返回 null。
     *
     * <p>read_file、grep 和这里的 bash 闸共用<b>同一张表</b>：只拦 bash 的话，模型换成 read_file
     * 一样把私钥读出来（安全评测里没有 hook 的 4 次里有 3 次就是这么读到的）。
     *
     * @param path 工作区相对路径（斜杠方向不限）
     */
    public static String secretIn(String path) {
        if (path == null) {
            return null;
        }
        // 前面补一个 /，相对路径 .ssh/config 才对得上 "/.ssh" 这种按目录写的片段
        String p = "/" + path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        for (String secret : SECRET_PATHS) {
            if (p.contains(secret)) {
                return secret;
            }
        }
        return null;
    }

    /** 系统目录，配合写操作就是 FORBIDDEN。 */
    private static final List<String> SYSTEM_PATHS = List.of(
            "/etc/", "/usr/", "/bin/", "/sbin/", "/System/", "/Library/", "/boot/", "/var/");

    /**
     * 会毁掉已有数据的命令。它们的目标只要指向工作区外，一律拒绝——
     * 这是防「把电脑删干净」的主力规则，比逐条列黑名单可靠。
     */
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "dd", "truncate", "shred", "srm", "tee", "ln",
            "chmod", "chown", "chgrp");

    /**
     * 这条命令的参数是不是「会被毁掉的东西」。
     *
     * <p>给 {@link RuleSuggester} 用，用来决定一条授权规则能不能带「后面还可以跟参数」
     * 那个通配。区别在于多出来的参数是什么性质：
     * <pre>
     *   mvn test -X        多出来的是选项    → 放宽无妨
     *   rm -rf target src  多出来的是<b>又一个删除目标</b> → 放宽等于多授权了一个目标
     * </pre>
     * 用这张现成的表而不是另列一份：它定义的正是「参数指向会被销毁的东西」，
     * 和这里要问的是同一件事。两份表迟早会分叉，而分叉处就是意外授权。
     */
    public static boolean destroysItsArguments(String commandName) {
        return commandName != null && DESTRUCTIVE_COMMANDS.contains(commandName.toLowerCase(Locale.ROOT));
    }

    /** 取一段命令里真正的命令名。{@link RuleSuggester} 要和这边认的是同一个。 */
    public static String commandNameOf(String segment) {
        return commandName(segment);
    }

    /** find 带这些参数就不再是只读命令了。 */
    private static final List<String> FIND_DESTRUCTIVE_FLAGS =
            List.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fls", "-fprint");

    /** 参数里出现这些，说明目标在工作区之外。 */
    private static final Pattern OUTSIDE_TARGET = Pattern.compile(
            // 路径前可以有个开引号：rm -rf "../outside" 和不带引号的是同一件事
            "(?:^|\\s)[\"']?(~|\\$HOME|\\$\\{HOME\\}|/[^\\s]|\\.\\.(?:/|\\s|$|[\"']))");

    /**
     * 重定向写到工作区外（/dev/null 除外，它无害且常用）。目标可以带开引号；
     * {@code ..} 后面得是路径分隔、空白、引号或结尾——{@code ..hidden.txt} 是工作区里的文件名。
     */
    private static final Pattern REDIRECT_OUTSIDE = Pattern.compile(
            ">>?\\s*[\"']?(~|\\$HOME|/(?!dev/null)|\\.\\.(?:[/\\\\\\s\"']|$))");

    /** 一个重定向：2>&1、> f、>> f、2> f、&> f、< f。只认独立成词的，a->b 这种参数里的不算。 */
    private static final Pattern REDIRECTION = Pattern.compile(
            "(?:^|\\s)(?:\\d+|&)?(?:>>?|<)\\s*(?:&\\d+|\\S+)");

    /** 去掉一段里的重定向：规则批的是命令，不是重定向的目标（目标另由 {@link #REDIRECT_OUTSIDE} 管）。 */
    static String stripRedirects(String segment) {
        return REDIRECTION.matcher(segment).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    /** 能改文件的命令，用来判断上面那些系统路径是被读还是被写。 */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "dd", "tee", "truncate", "ln",
            "chmod", "chown", "chgrp", "touch", "mkdir");

    /** 在原始命令上整体匹配的致命模式——拆段之后就看不出来了，所以必须先查。 */
    private static final List<Pattern> FATAL_PATTERNS = List.of(
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{.*\\|.*&"),               // fork bomb
            Pattern.compile("\\|\\s*(sudo\\s+)?(sh|bash|zsh|python\\d?)\\b"), // curl … | sh
            Pattern.compile(">\\s*/dev/(sd|disk|nvme|hd)"),                   // 直接写裸设备
            Pattern.compile("\\bof=/dev/"),                                   // dd of=/dev/…
            Pattern.compile("\\brm\\s+(-\\S+\\s+)*[~/]\\s*$"),                // rm … / 或 rm … ~
            Pattern.compile("\\brm\\s+(-\\S+\\s+)*/\\s+"),
            Pattern.compile("\\b(mv|cp)\\s+\\S+\\s+/dev/null\\b"));           // 把文件搬进黑洞

    /** rm 带这些参数时特别危险。 */
    private static final Pattern RM_RECURSIVE = Pattern.compile("\\brm\\s+(-\\S*[rR]\\S*\\s+)");
    /** 任何命令碰到家目录都要提醒一句。 */
    private static final Pattern HOME_REFERENCE = Pattern.compile("(?:^|\\s)(~|\\$HOME|\\$\\{HOME\\})");

    /** 会就地改文件的 sed。 */
    private static final Pattern SED_IN_PLACE = Pattern.compile("\\bsed\\s+(-\\S+\\s+)*-i\\b|\\bsed\\s+-\\S*i");

    public CommandRisk assess(String command) {
        if (command == null || command.isBlank()) {
            return CommandRisk.forbidden("命令为空");
        }
        String lower = command.toLowerCase(Locale.ROOT);

        // ---- 0. 重定向写到工作区外，等同于破坏外部文件 ----
        if (REDIRECT_OUTSIDE.matcher(lower).find()) {
            return CommandRisk.forbidden("命令试图把内容写到工作区之外的路径");
        }

        // ---- 1. 整体致命模式：拆段后就看不出来了，必须先查 ----
        for (Pattern p : FATAL_PATTERNS) {
            if (p.matcher(lower).find()) {
                return CommandRisk.forbidden("命令匹配到高危模式（删根目录 / 管道执行下载内容 / 写裸设备等）");
            }
        }
        for (String secret : SECRET_PATHS) {
            if (lower.contains(secret)) {
                return CommandRisk.forbidden("命令涉及密钥或系统凭据文件: " + secret);
            }
        }

        // ---- 2. 逐段判定，取最严的一档 ----
        RiskLevel worst = RiskLevel.SAFE;
        String reason = "";
        for (String segment : splitSegments(command)) {
            CommandRisk risk = assessSegment(segment);
            if (risk.isForbidden()) {
                return risk;                       // 有一段是禁止的，整条就禁止
            }
            if (risk.needsApproval() && worst == RiskLevel.SAFE) {
                worst = RiskLevel.NEEDS_APPROVAL;
                reason = risk.reason();
            }
        }
        if (worst != RiskLevel.SAFE) {
            return CommandRisk.approval(reason);
        }

        // ---- 3. 每段都无害，但整条的结构让人没法放心（照 Claude Code，这几种要问） ----
        if (command.length() > MAX_ANALYZED_LENGTH) {
            return CommandRisk.approval("命令超过 " + MAX_ANALYZED_LENGTH + " 个字符，分析不过来");
        }
        if (new QuoteAwareSplitter(command).split() == null) {
            return CommandRisk.approval("命令解析不了（引号或命令替换没配平）");
        }
        if (cdsAwayThenRunsGit(command)) {
            return CommandRisk.approval("cd 到别的目录再跑 git：那个目录的 .git/config 能让 git 执行任意命令");
        }
        return CommandRisk.safe();
    }

    /** 超过这么长就不分析了，直接问（照 Claude Code）。 */
    private static final int MAX_ANALYZED_LENGTH = 10_000;

    /** 前面某段 cd 到了别的目录（不是 . ），后面又跑 git。 */
    private static boolean cdsAwayThenRunsGit(String command) {
        boolean movedAway = false;
        for (String segment : splitSegments(command)) {
            String inner = unwrap(stripRedirects(segment), true).inner();
            String name = commandName(inner);
            if ("cd".equals(name) || "pushd".equals(name)) {
                String[] t = inner.trim().split("\\s+");
                String target = t.length > 1 ? t[1] : "";
                movedAway = movedAway || !(target.equals(".") || target.equals("./"));
            } else if ("git".equals(name) && movedAway) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按「形式」判 git，不按子命令名（照 Claude Code 的「read-only forms of git」）：
     * git remote 只读，git remote set-url 改了以后所有推送的去向；git branch 只读，git branch -D 删分支。
     */
    private static CommandRisk gitRisk(String segment) {
        String[] t = segment.trim().split("\\s+");
        int n = t.length;
        int i = 0;
        while (i < n && !t[i].substring(t[i].lastIndexOf('/') + 1).equalsIgnoreCase("git")) {
            i++;                                    // 跳过前缀赋值
        }
        i++;
        // 全局选项：-c 能把任意命令塞进 core.pager / alias；-C、--git-dir 换到别的仓库，那儿的配置能执行命令
        while (i < n && t[i].startsWith("-")) {
            String opt = t[i];
            if ("-c".equals(opt) || opt.startsWith("--config-env")) {
                return CommandRisk.approval("git -c 能借配置项执行任意命令");
            }
            if ("-C".equals(opt)) {
                String dir = i + 1 < n ? t[i + 1] : "";
                if (!(dir.equals(".") || dir.equals("./"))) {
                    return CommandRisk.approval("git -C 换到了别的目录：那儿的 .git/config 能让 git 执行任意命令");
                }
                i += 2;
                continue;
            }
            if (opt.startsWith("--git-dir") || opt.startsWith("--work-tree") || opt.startsWith("--exec-path")) {
                return CommandRisk.approval("git " + opt + " 换了仓库或执行路径");
            }
            i++;
        }
        if (i >= n) {
            return CommandRisk.safe();              // 光一个 git
        }
        String sub = t[i].toLowerCase(Locale.ROOT);
        List<String> rest = java.util.Arrays.asList(t).subList(i + 1, n);
        if (rest.stream().anyMatch(a -> a.startsWith("--output"))) {
            return CommandRisk.approval("git " + sub + " --output 会写文件");
        }
        boolean readOnly = READ_ONLY_GIT.contains(sub) || switch (sub) {
            case "remote" -> rest.isEmpty() || Set.of("-v", "--verbose", "show", "get-url").contains(rest.get(0));
            case "branch" -> rest.stream().noneMatch(BRANCH_MUTATING::contains)
                    && (rest.stream().anyMatch(BRANCH_QUERY::contains)
                        || rest.stream().allMatch(a -> a.startsWith("-")));   // 光带名字 = 建分支
            case "reflog" -> rest.isEmpty() || "show".equals(rest.get(0)) || rest.get(0).startsWith("-");
            case "stash" -> !rest.isEmpty() && Set.of("list", "show").contains(rest.get(0));
            case "worktree" -> !rest.isEmpty() && "list".equals(rest.get(0));
            case "config" -> rest.stream().anyMatch(CONFIG_READ::contains)
                    && rest.stream().noneMatch(CONFIG_WRITE::contains);
            default -> false;
        };
        return readOnly ? CommandRisk.safe() : CommandRisk.approval("git " + sub + " 会改动仓库状态或与远端交互");
    }

    /**
     * 包装命令：把后面的参数当命令跑。照 Claude Code 剥掉，按里面那条判——
     * 不剥的话，命令名是 env / nohup，里面的 curl、rm 就跟着它的名声一起过了。
     */
    private static final Set<String> WRAPPERS = Set.of(
            "timeout", "time", "nice", "nohup", "stdbuf", "command", "builtin", "xargs", "env");

    /** 给它们赋值会改变后面实际跑的是哪个程序、加载什么代码。 */
    private static final Set<String> SPECIAL_VARIABLES = Set.of(
            "PATH", "IFS", "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "BASH_ENV", "ENV",
            "PROMPT_COMMAND", "SHELLOPTS", "BASHOPTS", "PS4", "NODE_OPTIONS", "PYTHONPATH",
            "PYTHONSTARTUP", "PERL5OPT", "RUBYOPT", "GIT_SSH_COMMAND", "GIT_EXEC_PATH");

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    /**
     * 剥完包装的样子。
     *
     * @param inner      真正跑的那条（剥不动时是剩下的部分）
     * @param problem    剥不了、得问人的原因；能剥就是 null
     * @param specialVar 前面给哪个特殊变量赋了值；没有就是 null
     */
    record Unwrapped(String inner, String problem, String specialVar) {
    }

    /**
     * 剥掉前缀赋值和包装命令，套娃也剥到底：{@code nice nohup curl x} → {@code curl x}。
     *
     * @param pastAssignments false 时（给 allow 规则比对用）碰到赋值就整段原样返回：
     *                        赋值可能改变命令的行为，用户批的是不带它的那条。deny 规则用 true，越过任何赋值都要拦
     */
    static Unwrapped unwrap(String segment, boolean pastAssignments) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return new Unwrapped("", null, null);
        }
        String[] t = trimmed.split("\\s+");
        int n = t.length;
        int i = 0;
        String special = null;
        while (i < n && ASSIGNMENT.matcher(t[i]).matches()) {
            if (!pastAssignments) {
                return new Unwrapped(trimmed, null, null);
            }
            special = special != null ? special : specialVariable(t[i]);
            i++;
        }
        while (i < n) {
            String tok = t[i];
            String name = tok.substring(tok.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (!WRAPPERS.contains(name)) {
                break;
            }
            String next = i + 1 < n ? t[i + 1] : "";
            switch (name) {
                case "timeout" -> {                     // timeout [-s 信号] [-k 时长] 时长 命令
                    i++;
                    while (i < n && t[i].startsWith("-")) {
                        i += Set.of("-s", "-k", "--signal", "--kill-after").contains(t[i]) ? 2 : 1;
                    }
                    i++;                                // 时长
                }
                case "nice" -> {                        // nice [-n 数] 命令
                    i++;
                    while (i < n && t[i].startsWith("-")) {
                        i += "-n".equals(t[i]) ? 2 : 1;
                    }
                }
                case "stdbuf" -> {                      // stdbuf -oL 命令 / stdbuf -o L 命令
                    i++;
                    while (i < n && t[i].startsWith("-")) {
                        i += Set.of("-i", "-o", "-e").contains(t[i]) ? 2 : 1;
                    }
                }
                case "time" -> {
                    i++;
                    while (i < n && t[i].startsWith("-")) {
                        i++;
                    }
                }
                case "nohup" -> i++;
                case "command", "builtin" -> {
                    if ("-v".equals(next) || "-V".equals(next)) {
                        return new Unwrapped("", null, special);    // 查询形式：只是找命令在哪，不执行
                    }
                    i++;
                    while (i < n && "-p".equals(t[i])) {
                        i++;
                    }
                }
                case "xargs" -> {
                    if (next.startsWith("-")) {
                        return new Unwrapped(String.join(" ", java.util.Arrays.copyOfRange(t, i, n)),
                                null, special);     // 带参数的 xargs 不剥（照 Claude Code），按 xargs 本身判
                    }
                    i++;
                }
                case "env" -> {
                    i++;
                    while (i < n) {
                        String a = t[i];
                        if (Set.of("-i", "--ignore-environment", "-").contains(a) || a.startsWith("--unset=")) {
                            i++;
                        } else if ("-u".equals(a)) {
                            i += 2;
                        } else if (ASSIGNMENT.matcher(a).matches()) {
                            if (!pastAssignments) {
                                return new Unwrapped(trimmed, null, null);
                            }
                            special = special != null ? special : specialVariable(a);
                            i++;
                        } else if (a.startsWith("-")) {
                            return new Unwrapped(String.join(" ", java.util.Arrays.copyOfRange(t, i, n)),
                                    "env 带了认不全的参数 " + a + "，判断不了它会执行什么", special);
                        } else {
                            break;
                        }
                    }
                    if (i >= n) {
                        return new Unwrapped("", "env 不带命令会打印全部环境变量，里面可能有密钥", special);
                    }
                }
                default -> {
                    return new Unwrapped(String.join(" ", java.util.Arrays.copyOfRange(t, i, n)), null, special);
                }
            }
        }
        String inner = i >= n ? "" : String.join(" ", java.util.Arrays.copyOfRange(t, i, n));
        return new Unwrapped(inner, null, special);
    }

    private static String specialVariable(String assignment) {
        String name = assignment.substring(0, assignment.indexOf('='));
        return SPECIAL_VARIABLES.contains(name) || name.startsWith("DYLD_") ? name : null;
    }

    /**
     * 给规则比对用：拿掉重定向、剥掉包装之后的那条（照 Claude Code：规则批的是命令，不是重定向的目标）。
     * {@link #unwrap} 的 pastAssignments 见那里。
     */
    public static String unwrapForRules(String segment, boolean pastAssignments) {
        return unwrap(stripRedirects(segment), pastAssignments).inner();
    }

    /**
     * 这一段单拿出来，守卫会不会直接放行（既不禁止、也不用审批）。
     * 给 {@link ApprovalRules} 用：这样的段不需要授权规则罩着。
     */
    boolean segmentIsSafe(String segment) {
        CommandRisk risk = assessSegment(segment);
        return !risk.isForbidden() && !risk.needsApproval();
    }

    /**
     * 判一段：先剥包装，按里面那条判。禁止的照样禁止（剥出来的 sudo 不会因为前面多了个 PATH= 降级成问人），
     * 剥不了或者改了特殊变量的，至少要问。
     */
    private CommandRisk assessSegment(String segment) {
        Unwrapped u = unwrap(segment, true);
        CommandRisk inner = assessUnwrapped(u.inner());
        if (inner.isForbidden() || inner.needsApproval()) {
            return inner;
        }
        if (u.problem() != null) {
            return CommandRisk.approval(u.problem());
        }
        if (u.specialVar() != null) {
            return CommandRisk.approval("给 " + u.specialVar() + " 赋值会改变后面实际跑的是哪个程序");
        }
        return inner;
    }

    private CommandRisk assessUnwrapped(String segment) {
        String name = commandName(segment);
        if (name.isEmpty()) {
            return CommandRisk.safe();
        }
        String lower = segment.toLowerCase(Locale.ROOT);

        if (FORBIDDEN_COMMANDS.contains(name)) {
            return CommandRisk.forbidden(name + " 属于禁止执行的命令（提权 / 系统级操作）");
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (name.startsWith(prefix)) {
                return CommandRisk.forbidden(name + " 会格式化磁盘");
            }
        }

        // 写操作碰系统目录，直接拒绝
        if (WRITE_COMMANDS.contains(name)) {
            for (String path : SYSTEM_PATHS) {
                if (lower.contains(path)) {
                    return CommandRisk.forbidden(name + " 试图修改系统目录 " + path);
                }
            }
        }

        // 破坏性命令的目标只要指向工作区外，一律拒绝。
        // 这条比逐个列黑名单有效得多：不管是 rm -rf ~、mv ~/Documents /tmp
        // 还是 chmod -R 000 ~，统统落在这里。
        if (DESTRUCTIVE_COMMANDS.contains(name) && targetsOutside(segment, name)) {
            return CommandRisk.forbidden(name + " 的目标超出了工作区，只允许操作工作区内的文件");
        }

        // find 默认是只读的，但 -delete / -exec 让它变成删除或执行任意命令的工具
        if ("find".equals(name)) {
            boolean destructive = FIND_DESTRUCTIVE_FLAGS.stream().anyMatch(lower::contains);
            if (destructive && targetsOutside(segment, name)) {
                return CommandRisk.forbidden("find 带 -delete/-exec 且目标超出工作区");
            }
            if (destructive) {
                return CommandRisk.approval("find 带 -delete/-exec，会删除文件或执行任意命令");
            }
            if (targetsOutside(segment, name)) {
                return CommandRisk.approval("find 的搜索范围在工作区之外");
            }
            return CommandRisk.safe();
        }

        // 任何命令只要碰到家目录，都先问一句——那里全是个人数据
        if (HOME_REFERENCE.matcher(segment).find()) {
            return CommandRisk.approval(name + " 的参数指向家目录，可能影响工作区之外的个人文件");
        }

        if ("git".equals(name)) {
            return gitRisk(segment);
        }
        if (PACKAGE_MANAGERS.contains(name)) {
            // npm test / npm run build 是日常操作，npm install 才会下载并执行第三方脚本
            return bySubcommand(segment, SAFE_PACKAGE_SUBCOMMANDS, name, "会安装或发布依赖包");
        }
        if ("sed".equals(name) && SED_IN_PLACE.matcher(lower).find()) {
            return CommandRisk.approval("sed -i 会就地改写文件");
        }
        if (APPROVAL_COMMANDS.contains(name)) {
            return CommandRisk.approval(name + " 可能修改文件、访问网络或执行外部代码");
        }
        if (SAFE_COMMANDS.contains(name)) {
            return CommandRisk.safe();
        }
        // 白名单没列到的一律多问一句
        return CommandRisk.approval("未知命令 " + name + "，无法判断其影响");
    }

    /** 判断参数（不含命令名本身）里是否出现了工作区之外的路径。 */
    private static boolean targetsOutside(String segment, String name) {
        String args = stripCommandName(segment, name);
        return OUTSIDE_TARGET.matcher(args).find();
    }

    /** 去掉命令名，避免 /bin/rm 这种写法把命令自身的路径当成目标。 */
    private static String stripCommandName(String segment, String name) {
        String trimmed = segment.trim();
        int index = trimmed.toLowerCase(Locale.ROOT).indexOf(name);
        return index < 0 ? trimmed : trimmed.substring(index + name.length());
    }

    /**
     * 按子命令细分风险。像 git、npm 这类「一个命令干很多事」的工具，
     * 一刀切成危险会把 git status、npm test 这种日常操作也拦下来，
     * 用户被问烦了就会一路按 y，那道防线也就废了。
     */
    private CommandRisk bySubcommand(String segment, Set<String> safeSubcommands,
                                     String name, String reason) {
        for (String token : segment.trim().split("\\s+")) {
            if (token.isEmpty() || token.startsWith("-") || token.endsWith(name)) {
                continue;                          // 跳过命令自身和它的选项
            }
            String sub = token.toLowerCase(Locale.ROOT);
            return safeSubcommands.contains(sub)
                    ? CommandRisk.safe()
                    : CommandRisk.approval(name + " " + sub + " " + reason);
        }
        return CommandRisk.safe();                 // 光一个命令名，没带子命令
    }

    /**
     * 后台执行的单个 {@code &}。
     *
     * <p>{@code 2>&1}、{@code &>out} 里的 {@code &} 不是分隔符，切开会得到
     * {@code 2>} 这种「未知命令」，于是每条带重定向的命令都要问一次人——
     * 而被问烦了的用户会一路按同意，那才是真正的损失。
     */
    private static final Pattern BACKGROUND_AMP = Pattern.compile("(?<![<>&])&(?![>&0-9])");

    /**
     * 按分隔符拆段。
     *
     * <h3>认引号，照 bash 的规矩</h3>
     * 以前故意不认引号，宁可误报。安全评测里这代价很实在：{@code python -c "import a; print(1)"}
     * 被拆出「未知命令 print(1)」要审批，dontAsk 的子 agent 连验证都跑不了。现在：
     * <ul>
     *   <li>单引号里什么都不执行，整段是字面量；</li>
     *   <li>双引号里的 {@code ; | && 换行} 是字面量，<b>但 {@code $( )} 和反引号照样执行</b>——
     *       它们的内容单独拆成段去查，这是认引号最容易开的口子；</li>
     *   <li>反斜杠转义的字符是字面量；</li>
     *   <li>引号或替换没配平，就退回老办法（不认引号、见分隔符就拆）——宁可多拆。</li>
     * </ul>
     * 命令替换关上之后，后面的字接回外面那一段：{@code echo "today is $(date)"} 拆成
     * {@code echo "today is "} 和 {@code date}，而不是剩个孤零零的 {@code "} 被当成未知命令。
     *
     * <h3>为什么是 public</h3>
     * {@link ApprovalRules} 判断「这条命令在不在放行名单里」时，<b>必须用同一个拆段器</b>。
     * 两边各拆各的，它们之间的差值就是绕过这套系统的入口：
     * 风险评估拆成两段、看见了危险的那段，放行名单不拆、只看见前缀像 {@code mvn test}，
     * 于是危险的那段被前缀规则顺手批了。
     *
     * <p>共用一份不是为了少写几行，是为了让这个差值<b>恒等于零</b>。
     */
    public static List<String> splitSegments(String command) {
        List<String> quoted = new QuoteAwareSplitter(command).split();
        return quoted != null ? quoted : splitIgnoringQuotes(command);
    }

    /** 一个字一个字走，记着自己在哪层引号 / 命令替换里。没配平返回 null。 */
    private static final class QuoteAwareSplitter {
        private static final char TOP = 'T', SUBST = '(', BACKTICK = '`', DOUBLE = '"', SINGLE = '\'';

        private final String s;
        private final List<String> out = new java.util.ArrayList<>();
        /** 每层命令替换一个：正在攒的那一段。顶层也算一层。 */
        private final java.util.Deque<StringBuilder> segments = new java.util.ArrayDeque<>();
        /** 现在在什么里面：顶层、$( 、反引号、双引号、单引号。 */
        private final java.util.Deque<Character> context = new java.util.ArrayDeque<>();

        QuoteAwareSplitter(String s) {
            this.s = s;
            segments.push(new StringBuilder());
            context.push(TOP);
        }

        List<String> split() {
            int n = s.length();
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                char next = i + 1 < n ? s.charAt(i + 1) : 0;
                char where = context.peek();
                if (where == SINGLE) {                      // 单引号里只认关引号，连反斜杠都是字面量
                    if (c == '\'') {
                        context.pop();
                    }
                    append(c);
                    continue;
                }
                if (c == '\\' && i + 1 < n) {               // 转义：下一个字原样收下
                    append(c);
                    append(next);
                    i++;
                    continue;
                }
                if (c == '$' && next == '(') {              // 双引号里也执行
                    open(SUBST);
                    i++;
                    continue;
                }
                if (c == '`') {
                    if (where == BACKTICK) {
                        close();
                    } else {
                        open(BACKTICK);
                    }
                    continue;
                }
                if (where == DOUBLE) {
                    if (c == '"') {
                        context.pop();
                    }
                    append(c);
                    continue;
                }
                // 不在引号里：顶层、$( 里、反引号里
                if (c == '\'' || c == '"') {
                    context.push(c);
                    append(c);
                } else if (c == ')') {
                    if (where == SUBST) {
                        close();
                    } else {
                        cut();                              // 顶层的 ) 照老规矩当分隔：(cd a; rm b)
                    }
                } else if (c == ';' || c == '|' || c == '\n') {
                    cut();
                } else if (c == '&') {
                    char prev = i > 0 ? s.charAt(i - 1) : 0;
                    if (next == '&') {
                        cut();
                        i++;
                    } else if (prev == '<' || prev == '>' || next == '>' || Character.isDigit(next)) {
                        append(c);                          // 2>&1、&>out 里的 & 不是分隔符
                    } else {
                        cut();                              // 单个 & 是后台运行，也是一段分界
                    }
                } else {
                    append(c);
                }
            }
            if (context.size() != 1) {
                return null;                                // 引号或替换没配平
            }
            cut();
            return out;
        }

        private void append(char c) {
            segments.peek().append(c);
        }

        /** 这一段到头了，下一段从这层接着攒。 */
        private void cut() {
            out.add(segments.pop().toString());
            segments.push(new StringBuilder());
        }

        /** 进一层命令替换：里面的内容是独立的段。 */
        private void open(char kind) {
            context.push(kind);
            segments.push(new StringBuilder());
        }

        /** 出这层命令替换：它攒的那段交出去，接回外面那一段。 */
        private void close() {
            context.pop();
            out.add(segments.pop().toString());
        }
    }

    /** 不认引号、见分隔符就拆。引号没配平时的退路——宁可多拆。 */
    private static List<String> splitIgnoringQuotes(String command) {
        String normalized = command
                .replace("&&", "\n").replace("||", "\n")
                .replace("$(", "\n").replace("`", "\n")
                .replace(";", "\n").replace("|", "\n")
                .replace(")", "\n");
        // 单个 & 也是分隔符。漏掉它的后果实测过：mvn test & rm -rf build
        // 整条被判成 SAFE——因为 commandName 只看第一个 token，看到 mvn 就收工了
        normalized = BACKGROUND_AMP.matcher(normalized).replaceAll("\n");
        return List.of(normalized.split("\n"));
    }

    /** 取一段里真正的命令名：跳过 FOO=bar 这类前缀赋值，并去掉路径。 */
    private static String commandName(String segment) {
        for (String token : segment.trim().split("\\s+")) {
            if (token.isEmpty() || (token.contains("=") && !token.startsWith("-"))) {
                continue;
            }
            int slash = token.lastIndexOf('/');
            String name = slash >= 0 ? token.substring(slash + 1) : token;
            return name.toLowerCase(Locale.ROOT);
        }
        return "";
    }
}
