package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Exact string replacement in an existing file.
 */
@Component
public class EditFileTool implements Tool {

    private final Path workspace;
    private final AgentProperties.File cfg;

    public EditFileTool(Path agentWorkspace, AgentProperties props) {
        this.workspace = agentWorkspace;
        this.cfg = props.getTools().getFile();
    }

    @Override public String name() { return "edit_file"; }

    @Override public String description() {
        return "Replace an exact string in a file. `old_string` must appear exactly once (unless `replace_all=true`). " +
               "Preserve whitespace carefully. For large rewrites, prefer write_file.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path",        Map.of("type", "string"),
                        "old_string",  Map.of("type", "string"),
                        "new_string",  Map.of("type", "string"),
                        "replace_all", Map.of("type", "boolean", "default", false)
                ),
                "required", List.of("path", "old_string", "new_string")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        try {
            if (!cfg.isEnabled()) return ToolResult.error(callId, "File tool is disabled.");

            Path file = WorkspacePath.resolve(workspace, (String) args.get("path"));
            if (!Files.exists(file)) return ToolResult.error(callId, "File not found: " + args.get("path"));

            String original  = Files.readString(file, StandardCharsets.UTF_8);
            String oldString = (String) args.get("old_string");
            String newString = (String) args.getOrDefault("new_string", "");
            boolean all      = Boolean.TRUE.equals(args.get("replace_all"));

            if (oldString == null || oldString.isEmpty()) {
                return ToolResult.error(callId, "old_string is required");
            }

            int count = countOccurrences(original, oldString);
            if (count == 0) return ToolResult.error(callId, "old_string not found in file");
            if (count > 1 && !all) {
                return ToolResult.error(callId,
                        "old_string appears " + count + " times; use replace_all=true or add more surrounding context.");
            }

            String updated = all ? original.replace(oldString, newString)
                                 : replaceFirstLiteral(original, oldString, newString);
            Files.writeString(file, updated, StandardCharsets.UTF_8);

            return ToolResult.ok(callId,
                    "Replaced " + (all ? count : 1) + " occurrence(s) in " +
                            WorkspacePath.displayRelative(workspace, file));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int idx = 0, count = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }

    private static String replaceFirstLiteral(String s, String find, String repl) {
        int i = s.indexOf(find);
        return i < 0 ? s : s.substring(0, i) + repl + s.substring(i + find.length());
    }
}
