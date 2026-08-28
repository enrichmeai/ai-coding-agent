package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Component
public class ShellTool implements Tool {

    private final Path workspace;
    private final AgentProperties.Shell cfg;
    private final List<Pattern> blockedPatterns;

    public ShellTool(Path agentWorkspace, AgentProperties props) {
        this.workspace = agentWorkspace;
        this.cfg = props.getTools().getShell();
        this.blockedPatterns = cfg.getBlockedPatterns().stream().map(Pattern::compile).toList();
    }

    /** Head kept verbatim; the rest of a long run is represented by its tail. */
    private static final int HEAD_LIMIT_CHARS = 30_000;
    private static final int TAIL_LIMIT_CHARS = 30_000;

    @Override public String name() { return "shell"; }

    /**
     * Extract the first token (command name) from a shell command.
     * Strips leading "./" or full paths to get the executable name.
     * E.g. "./gradlew" -> "gradlew", "/usr/bin/grep" -> "grep", "ls" -> "ls".
     */
    private String extractFirstToken(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) return "";
        String token = parts[0];
        // Strip leading "./"
        if (token.startsWith("./")) {
            token = token.substring(2);
        }
        // For paths like "/usr/bin/grep", extract just "grep"
        if (token.contains("/")) {
            token = token.substring(token.lastIndexOf('/') + 1);
        }
        return token;
    }

    @Override public String description() {
        String desc = "Run a shell command in the workspace directory. Returns combined stdout/stderr. " +
                "Default timeout: " + cfg.getTimeoutSeconds() + "s. " +
                "Use this to build, test, lint, or inspect the repo (e.g. 'ls', 'git status', " +
                "'gradle test' — the container ships Gradle on PATH; './gradlew test' works " +
                "only where the wrapper jar has been bootstrapped).";
        if (!cfg.getAllowedCommands().isEmpty()) {
            desc += " [allow-list enforced]";
        }
        return desc;
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Shell command to execute."),
                        "timeout_seconds", Map.of("type", "integer", "description", "Override timeout.")
                ),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        if (!cfg.isEnabled()) return ToolResult.error(callId, "Shell tool is disabled.");

        String command = (String) args.get("command");
        if (command == null || command.isBlank()) return ToolResult.error(callId, "command is required");

        for (Pattern p : blockedPatterns) {
            if (p.matcher(command).find()) {
                return ToolResult.error(callId, "Command blocked by policy: matches " + p.pattern());
            }
        }

        // Check allow-list if configured
        List<String> allowedList = cfg.getAllowedCommands();
        if (!allowedList.isEmpty()) {
            String token = extractFirstToken(command);
            if (!allowedList.contains(token)) {
                return ToolResult.error(callId, "Command not in allow-list: " + token + ". Configured allow-list: " + allowedList);
            }
        }

        int timeoutSec = args.get("timeout_seconds") instanceof Number n
                ? n.intValue() : cfg.getTimeoutSeconds();

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);

        try {
            Process process = pb.start();

            // The timeout has to be enforced by a watchdog rather than by waiting
            // after the read. Draining stdout blocks until the stream closes, which
            // for a command that keeps printing — a Gradle build, say — is when it
            // finishes. Checking the clock only afterwards would let a long build
            // hold the turn for its full duration and then report success.
            AtomicBoolean timedOut = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "shell-timeout-" + callId);
            watchdog.setDaemon(true);
            watchdog.start();

            // Keep the head AND the tail. A build prints its dependency noise first
            // and the reason it failed last, so discarding everything after a
            // head-only cap threw away the only part worth reading.
            StringBuilder head = new StringBuilder();
            ArrayDeque<String> tail = new ArrayDeque<>();
            int tailChars = 0;
            boolean dropped = false;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (head.length() < HEAD_LIMIT_CHARS) {
                        head.append(line).append('\n');
                        continue;
                    }
                    tail.addLast(line);
                    tailChars += line.length() + 1;
                    while (tailChars > TAIL_LIMIT_CHARS && tail.size() > 1) {
                        tailChars -= tail.removeFirst().length() + 1;
                        dropped = true;
                    }
                }
            }
            StringBuilder output = new StringBuilder(head);
            if (dropped) {
                output.append("\n... [middle of output dropped] ...\n\n");
            }
            for (String line : tail) {
                output.append(line).append('\n');
            }
            process.waitFor();
            watchdog.interrupt();

            if (timedOut.get()) {
                return ToolResult.error(callId,
                        "Command timed out after " + timeoutSec + "s and was killed.\n"
                        + "Pass a larger timeout_seconds if it legitimately takes longer.\n" + output);
            }
            int exit = process.exitValue();
            String result = "exit=" + exit + "\n" + output;
            return exit == 0 ? ToolResult.ok(callId, result) : ToolResult.error(callId, result);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(callId, "Shell execution failed: " + e.getMessage());
        }
    }
}
