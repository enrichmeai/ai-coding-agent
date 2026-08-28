package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Regex search across files. Java-native (no ripgrep dependency).
 */
@Component
public class GrepTool implements Tool {

    private static final int MAX_MATCHES = 500;

    private final Path workspace;

    public GrepTool(Path agentWorkspace) {
        this.workspace = agentWorkspace;
    }

    @Override public String name() { return "grep"; }

    @Override public String description() {
        return "Search for a regex across files in the workspace. Returns file:line:content hits (up to " +
                MAX_MATCHES + ").";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern",        Map.of("type", "string", "description", "Java regex to match."),
                        "path",           Map.of("type", "string", "description", "Base directory (defaults to workspace root)."),
                        "case_insensitive", Map.of("type", "boolean", "default", false),
                        "include_glob",   Map.of("type", "string", "description", "Optional glob filter e.g. '**/*.java'.")
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        try {
            String patternStr = (String) args.get("pattern");
            Pattern pattern = Boolean.TRUE.equals(args.get("case_insensitive"))
                    ? Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(patternStr);

            Path base = args.get("path") != null
                    ? WorkspacePath.resolve(workspace, (String) args.get("path"))
                    : workspace;

            var matcher = args.get("include_glob") != null
                    ? java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + args.get("include_glob"))
                    : null;

            StringBuilder out = new StringBuilder();
            int hits = 0;

            try (Stream<Path> walk = Files.walk(base)) {
                var paths = walk.filter(Files::isRegularFile).toList();
                outer:
                for (Path p : paths) {
                    Path rel = base.relativize(p);
                    if (matcher != null && !matcher.matches(rel)) continue;
                    if (isLikelyBinary(p)) continue;

                    List<String> lines;
                    try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); }
                    catch (Exception e) { continue; }

                    for (int i = 0; i < lines.size(); i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            out.append(WorkspacePath.displayRelative(workspace, p))
                               .append(':').append(i + 1).append(": ")
                               .append(truncate(lines.get(i), 300)).append('\n');
                            hits++;
                            if (hits >= MAX_MATCHES) {
                                out.append("\n... (match limit reached)\n");
                                break outer;
                            }
                        }
                    }
                }
            }

            if (hits == 0) return ToolResult.ok(callId, "No matches.");
            out.insert(0, "Found " + hits + " match(es):\n");
            return ToolResult.ok(callId, out.toString());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    private boolean isLikelyBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(512);
            for (byte b : head) if (b == 0) return true;
            return false;
        } catch (IOException e) { return true; }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
