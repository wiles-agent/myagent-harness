package dev.wiles.myagent.hooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 跑一条 hook 命令：stdin 喂一段 JSON，拿回退出码、stdout、stderr。
 * {@link PromptHooks}（UserPromptSubmit）和 {@link ToolHooks}（PreToolUse）共用这一份，约定和 Claude Code 一致。
 */
public final class HookRunner {

    private HookRunner() {
    }

    /**
     * @param error 起不来、超时、被中断时的说明；正常跑完是 null（退出码再非零也算正常跑完）
     */
    public record Result(int exit, String stdout, String stderr, String error) {
    }

    /**
     * 命令先写进一个临时脚本再交给 sh，而不是 {@code sh -c 命令}：Windows 上 Java 拼命令行时
     * 不转义参数里的双引号，{@code echo '{"decision":"block"}'} 这种 hook 一传过去就断了。
     */
    public static Result run(String command, int timeoutSeconds, Path cwd, byte[] stdin) {
        Path script;
        try {
            script = Files.createTempFile("myagent-hook-", ".sh");
            Files.writeString(script, command + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(-1, "", "", "写不了临时脚本: " + e.getMessage());
        }
        try {
            return run(script, timeoutSeconds, cwd, stdin);
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException e) {
                script.toFile().deleteOnExit();
            }
        }
    }

    private static Result run(Path script, int timeoutSeconds, Path cwd, byte[] stdin) {
        Process process;
        try {
            process = new ProcessBuilder("sh", script.toAbsolutePath().toString())
                    .directory(cwd.toFile()).start();
        } catch (IOException e) {
            return new Result(-1, "", "", "起不来: " + e.getMessage());
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin);
        } catch (IOException e) {
            // 命令不读 stdin 就先关了管道，这不算错
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "", "", "超时（" + timeoutSeconds + " 秒），没等它");
            }
            return new Result(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "", "被中断");
        } catch (Exception e) {
            return new Result(-1, "", "", "读输出失败: " + e.getMessage());
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                stream.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
