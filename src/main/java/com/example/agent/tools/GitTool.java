package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrow git wrapper — whitelists safe subcommands and delegates to {@link ShellTool}.
 */
@Component
public class GitTool implements Tool {

    private static final Set<String> ALLOWED = Set.of(
            "status", "diff", "log", "show", "branch", "add", "commit", "checkout",
            "ls-files", "blame", "stash", "restore", "rev-parse", "remote", "fetch", "pull"
    );

    private final ShellTool shellTool;

    public GitTool(ShellTool shellTool) {
        this.shellTool = shellTool;
    }

    @Override public String name() { return "git"; }

    @Override public String description() {
        return "Run a git subcommand in the workspace. Allowed subcommands: " + ALLOWED +
                ". Example: subcommand='status', args=['--short'].";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "subcommand", Map.of("type", "string", "description", "git subcommand (e.g. status, diff)."),
                        "args",       Map.of("type", "array", "items", Map.of("type", "string"),
                                             "description", "Additional arguments passed to git.")
                ),
                "required", List.of("subcommand")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> arguments) {
        String sub = (String) arguments.get("subcommand");
        if (sub == null || !ALLOWED.contains(sub)) {
            return ToolResult.error(callId, "Git subcommand not allowed: " + sub + ". Allowed: " + ALLOWED);
        }
        @SuppressWarnings("unchecked")
        List<String> extra = (List<String>) arguments.getOrDefault("args", List.of());

        StringBuilder cmd = new StringBuilder("git ").append(sub);
        for (String a : extra) {
            cmd.append(' ').append(shellQuote(a));
        }
        return shellTool.execute(callId, Map.of("command", cmd.toString()));
    }

    private static String shellQuote(String s) {
        if (s.matches("[A-Za-z0-9_./=@:-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
