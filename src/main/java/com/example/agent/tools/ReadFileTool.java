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

@Component
public class ReadFileTool implements Tool {

    private final Path workspace;
    private final AgentProperties.File cfg;

    public ReadFileTool(Path agentWorkspace, AgentProperties props) {
        this.workspace = agentWorkspace;
        this.cfg = props.getTools().getFile();
    }

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "Read the contents of a text file inside the workspace. Returns up to " +
                cfg.getMaxBytes() + " bytes. Optional `offset` (0-based line number) and `limit` " +
                "(max lines to return) for paging large files.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path",   Map.of("type", "string", "description", "Path to the file (relative to workspace)."),
                        "offset", Map.of("type", "integer", "description", "Line to start reading from (default 0).", "default", 0),
                        "limit",  Map.of("type", "integer", "description", "Max lines to read (default 2000).", "default", 2000)
                ),
                "required", List.of("path")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) {
        try {
            if (!cfg.isEnabled()) return ToolResult.error(callId, "File tool is disabled.");

            Path file = WorkspacePath.resolve(workspace, (String) args.get("path"));
            if (!Files.exists(file))   return ToolResult.error(callId, "File not found: " + args.get("path"));
            if (Files.isDirectory(file)) return ToolResult.error(callId, "Path is a directory: " + args.get("path"));
            if (Files.size(file) > cfg.getMaxBytes()) {
                return ToolResult.error(callId, "File exceeds max size (" + cfg.getMaxBytes() + " bytes).");
            }

            int offset = toInt(args.get("offset"), 0);
            int limit  = toInt(args.get("limit"), 2000);

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.min(offset, lines.size());
            int to   = Math.min(from + limit, lines.size());

            StringBuilder out = new StringBuilder();
            out.append("File: ").append(WorkspacePath.displayRelative(workspace, file))
               .append("  (lines ").append(from + 1).append("-").append(to)
               .append(" of ").append(lines.size()).append(")\n");
            for (int i = from; i < to; i++) {
                out.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
            }
            return ToolResult.ok(callId, out.toString());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) try { return Integer.parseInt(s); } catch (NumberFormatException ignore) {}
        return def;
    }
}
