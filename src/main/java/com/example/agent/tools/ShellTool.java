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
import java.util.concurrent.TimeUnit;
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
                "Use this to build, test, lint, or inspect the repo (e.g. 'ls', 'git status', './gradlew test').";
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
    public ToolResult execute(String callId, Map<String, Object> args) {
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
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (output.length() < 60_000) output.append(line).append('\n');
                }
            }
            boolean done = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return ToolResult.error(callId, "Command timed out after " + timeoutSec + "s.\n" + output);
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
