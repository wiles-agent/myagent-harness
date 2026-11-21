package dev.wiles.myagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子 agent 的临时 git worktree（类型定义里的 {@code isolation: worktree}，照 Claude Code）。
 *
 * <h3>放在哪、从哪拉</h3>
 * 放在系统临时目录，<b>不放工作区里</b>：放进去的话主 agent 一 grep 就搜到两份代码。
 * 照 Claude Code 从<b>默认分支</b>拉（origin/HEAD 指向的那个，没有就 main / master），不从主对话当前的 HEAD 拉——
 * 子 agent 拿到的是一份干净的基线，主对话手上没提交的改动不会混进它的那份里。都找不到才退回 HEAD。
 *
 * <h3>什么时候收</h3>
 * 没改动（没有未提交的改动、也没有比基线多出的提交）就整个收掉，分支一起删；有改动就留着，
 * 路径和分支写进通知，交给主 agent 去合并——删掉别人的劳动成果比多留一个目录糟得多。
 */
final class GitWorktree {

    private static final Logger log = LoggerFactory.getLogger(GitWorktree.class);
    private static final long GIT_TIMEOUT_SECONDS = 60;

    private final Path repo;
    private final Path path;
    private final Path holder;
    private final String branch;
    private final String base;

    private GitWorktree(Path repo, Path path, Path holder, String branch, String base) {
        this.repo = repo;
        this.path = path;
        this.holder = holder;
        this.branch = branch;
        this.base = base;
    }

    Path path() {
        return path;
    }

    String branch() {
        return branch;
    }

    String base() {
        return base;
    }

    private record Result(int exit, String out) {
    }

    private static Result git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "git 超时");
            }
            return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            return new Result(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "被中断");
        }
    }

    /** {@code dir} 在不在一个 git 仓库的工作区里。 */
    static boolean isRepo(Path dir) {
        Result r = git(dir, "rev-parse", "--is-inside-work-tree");
        return r.exit() == 0 && "true".equals(r.out());
    }

    /** 默认分支：origin/HEAD 指向的，没有就本地的 main / master，都没有退回 HEAD。 */
    static String defaultBranch(Path repo) {
        Result remote = git(repo, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD");
        if (remote.exit() == 0 && !remote.out().isBlank()) {
            return remote.out();
        }
        for (String local : List.of("main", "master")) {
            if (git(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + local).exit() == 0) {
                return local;
            }
        }
        return "HEAD";
    }

    /**
     * 拉一个新的 worktree，分支叫 {@code myagent/<id>}（重名就加时间戳）。
     *
     * @throws IOException 建不出来（不是 git 仓库、git 报错）
     */
    static GitWorktree create(Path repo, String id) throws IOException {
        String base = defaultBranch(repo);
        Result baseSha = git(repo, "rev-parse", base);
        if (baseSha.exit() != 0) {
            throw new IOException("找不到基线 " + base + ": " + baseSha.out());
        }
        String branch = "myagent/" + id;
        if (git(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).exit() == 0) {
            branch = branch + "-" + System.currentTimeMillis();
        }
        Path holder = Files.createTempDirectory("myagent-wt-" + id + "-");
        Path path = holder.resolve("worktree");
        Result add = git(repo, "worktree", "add", "-q", "-b", branch, path.toString(), baseSha.out());
        if (add.exit() != 0) {
            Files.deleteIfExists(holder);
            throw new IOException("git worktree add 失败: " + add.out());
        }
        return new GitWorktree(repo, path, holder, branch, baseSha.out());
    }

    /** 有没有改动：未提交的改动，或者比基线多出的提交。git 出错时按「有改动」算——宁可多留一个目录。 */
    boolean hasChanges() {
        Result status = git(path, "status", "--porcelain");
        if (status.exit() != 0 || !status.out().isBlank()) {
            return true;
        }
        Result ahead = git(path, "rev-list", "--count", base + "..HEAD");
        return ahead.exit() != 0 || !"0".equals(ahead.out());
    }

    /** 没改动就收掉 worktree 和分支，返回收了没有；有改动就留着。 */
    boolean removeIfClean() {
        if (!Files.exists(path) || hasChanges()) {
            return false;
        }
        Result remove = git(repo, "worktree", "remove", "--force", path.toString());
        if (remove.exit() != 0) {
            log.warn("收不掉 worktree {}: {}", path, remove.out());
            return false;
        }
        git(repo, "branch", "-D", branch);
        try {
            Files.deleteIfExists(holder);
        } catch (IOException e) {
            holder.toFile().deleteOnExit();
        }
        return true;
    }
}
