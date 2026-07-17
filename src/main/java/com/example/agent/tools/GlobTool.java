package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Component
public class GlobTool implements Tool {

    private final Path workspace;

    public GlobTool(Path agentWorkspace) {
        this.workspace = agentWorkspace;
    }

    @Override public String name() { return "glob"; }

    @Override public String description() {
        return "Find files matching a glob pattern (e.g. '**/*.java', 'src/**/*.yml'). Returns up to 500 paths.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Glob pattern."),
                        "path",    Map.of("type", "string", "description", "Base directory (defaults to workspace root).")
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) {
        try {
            String pattern = (String) args.get("pattern");
            Path base = args.get("path") != null
                    ? WorkspacePath.resolve(workspace, (String) args.get("path"))
                    : workspace;
            if (!Files.isDirectory(base)) return ToolResult.error(callId, "Not a directory: " + base);

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matches = new ArrayList<>();
            try (java.util.stream.Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    Path rel = base.relativize(p);
                    if (matcher.matches(rel)) matches.add(p);
                });
            }

            matches.sort(Comparator.comparingLong(this::lastModifiedSafe).reversed());
            int shown = Math.min(matches.size(), 500);

            StringBuilder out = new StringBuilder();
            out.append("Matched ").append(matches.size()).append(" files")
               .append(shown < matches.size() ? " (showing first " + shown + "):\n" : ":\n");
            for (int i = 0; i < shown; i++) {
                out.append(WorkspacePath.displayRelative(workspace, matches.get(i))).append('\n');
            }
            return ToolResult.ok(callId, out.toString());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    private long lastModifiedSafe(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }
}
