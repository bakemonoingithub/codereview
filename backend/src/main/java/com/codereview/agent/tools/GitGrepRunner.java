package com.codereview.agent.tools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 受限 {@code git grep}：参数数组直接交给进程，不经过 shell。
 *
 * <p>固定 {@code -F -n -I}：字面量匹配、原始行号、跳过二进制；查询写入临时文件后经 {@code -f} 传入，
 * 用户输入不进入命令行；路径作为 {@code --literal-pathspecs} 后的字面路径，不会被拼成选项或
 * pathspec 魔法。退出码 1 表示"确实没有匹配"，其他非零是失败；超时或输出超过上限时终止进程
 * 并明确标记，不把半截输出当成完整结果。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class GitGrepRunner {

    /** 单次 grep 的原始输出上限；超过即终止进程并标记截断。 */
    static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_BYTES = 8 * 1024;

    private final ToolProperties properties;

    public GitGrepRunner(ToolProperties properties) {
        this.properties = properties;
    }

    public Result search(File mirrorDir, String sha, String query, String pathPrefix, Duration timeout) {
        return search(mirrorDir, sha, query, pathPrefix, timeout, MAX_OUTPUT_BYTES);
    }
    public Result search(File mirrorDir, String sha, String query, String pathPrefix, Duration timeout, int maxOutputBytes) {
        // 查询内容写入临时文件，经 -f 传入：用户输入不进入命令行，
        // 也避免 Windows 上 Java ProcessBuilder 对参数内引号／反斜杠的转义差异。
        File patternFile;
        try {
            patternFile = File.createTempFile("agent-grep-", ".txt");
            Files.writeString(patternFile.toPath(), query + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Result.failure("GIT_GREP_FAILED", "无法写入临时查询文件：" + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add(property(properties.getGitExecutable(), "git"));
        command.add("--no-pager");
        command.add("-C");
        command.add(mirrorDir.getAbsolutePath());
        command.add("--literal-pathspecs");
        command.add("grep");
        command.add("-F");
        command.add("-n");
        command.add("-I");
        command.add("-z");
        command.add("-f");
        command.add(patternFile.getAbsolutePath());
        command.add(sha);
        command.add("--");
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            command.add(pathPrefix);
        }

        try {
            return run(command, sha, timeout, maxOutputBytes);
        } finally {
            try {
                Files.deleteIfExists(patternFile.toPath());
            } catch (IOException ignored) {
                // 临时文件删除失败不影响工具结果，只留下一个空查询文件。
            }
        }
    }

    private Result run(List<String> command, String sha, Duration timeout, int maxOutputBytes) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Result.failure("GIT_UNAVAILABLE", "无法启动 git：" + e.getMessage());
        }

        BoundedStream stdout = new BoundedStream(process.getInputStream(), maxOutputBytes);
        BoundedStream stderr = new BoundedStream(process.getErrorStream(), MAX_DIAGNOSTIC_BYTES);
        Thread outThread = new Thread(stdout, "git-grep-stdout");
        Thread errThread = new Thread(stderr, "git-grep-stderr");
        outThread.start();
        errThread.start();

        boolean timedOut = false;
        int exitCode;
        try {
            long millis = Math.max(1, timeout == null ? 10_000 : timeout.toMillis());
            if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            timedOut = true;
        }
        try {
            outThread.join(2000);
            errThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exitCode = process.isAlive() ? -1 : process.exitValue();

        if (timedOut) {
            return new Result(List.of(), stdout.overflowed(), true, exitCode,
                    "搜索超时，已终止进程");
        }
        if (stdout.overflowed()) {
            return new Result(parse(stdout.text(), sha), true, false, exitCode,
                    "搜索结果超过输出上限，已终止读取");
        }
        if (exitCode == 1) {
            return new Result(List.of(), false, false, exitCode, null);
        }
        if (exitCode != 0) {
            return Result.failure("GIT_GREP_FAILED",
                    "git grep 失败（退出码 " + exitCode + "）：" + stderr.text().trim());
        }
        return new Result(parse(stdout.text(), sha), false, false, exitCode, null);
    }

    private static List<Hit> parse(String output, String sha) {
        List<Hit> hits = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\u0000", -1);
            if (fields.length < 3) {
                continue;
            }
            String path = fields[0];
            String prefix = sha + ":";
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            }
            int lineNumber;
            try {
                lineNumber = Integer.parseInt(fields[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            hits.add(new Hit(path, lineNumber, fields[2]));
        }
        return hits;
    }

    private static String property(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 一条命中：仓库内路径、原始行号与整行文本（可能很长，调用方负责截短）。 */
    public record Hit(String path, int lineNumber, String text) {
    }

    /**
     * 搜索结果；{@code truncated/timedOut} 都表示不能把结果当成"已搜索全范围"，
     * {@code diagnostic} 只含命令错误，不含凭据。
     */
    public record Result(List<Hit> hits, boolean truncated, boolean timedOut, int exitCode,
                         String diagnostic) {

        public Result {
            hits = List.copyOf(hits);
        }

        public static Result failure(String code, String message) {
            return new Result(List.of(), true, false, -1, code + "：" + message);
        }
    }

    /** 有界读取：超过上限后继续排空但不保留，子进程因管道写满被终止。 */
    private static final class BoundedStream implements Runnable {

        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean overflowed;

        private BoundedStream(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    if (buffer.size() + read > maxBytes) {
                        int keep = Math.max(0, maxBytes - buffer.size());
                        buffer.write(chunk, 0, keep);
                        overflowed = true;
                        continue;
                    }
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // 进程被终止时读取会失败；结果已标记为超时/截断，这里不再覆盖。
            }
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        boolean overflowed() {
            return overflowed;
        }
    }

    /** 便于测试与诊断的只读视图。 */
    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("executable", property(properties.getGitExecutable(), "git"));
        description.put("maxOutputBytes", MAX_OUTPUT_BYTES);
        return description;
    }
}
