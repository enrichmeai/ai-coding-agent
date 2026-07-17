package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ListDirTool implements Tool {

    private final Path workspace;

    public ListDirTool(Path agentWorkspace) {
        this.workspace = agentWorkspace;
    }

    @Override public String name() { return "list_dir"; }

    @Override public String description() {
        return "List immediate contents of a directory. Directories are marked with a trailing '/'.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Directory path (defaults to workspace root).")
                ),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) {
        try {
            Path dir = args.get("path") != null && !((String) args.get("path")).isBlank()
                    ? WorkspacePath.resolve(workspace, (String) args.get("path"))
                    : workspace;
            if (!Files.isDirectory(dir)) return ToolResult.error(callId, "Not a directory: " + dir);

            StringBuilder out = new StringBuilder();
            out.append(WorkspacePath.displayRelative(workspace, dir)).append("/\n");
            try (Stream<Path> children = Files.list(dir)) {
                children.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> out.append(p.getFileName())
                                         .append(Files.isDirectory(p) ? "/" : "")
                                         .append('\n'));
            }
            return ToolResult.ok(callId, out.toString());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }
}
