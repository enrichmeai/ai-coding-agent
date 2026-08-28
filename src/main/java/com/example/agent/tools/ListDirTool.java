package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ListDirTool implements Tool {

    /**
     * Directories that dominate a checkout by entry count and tell a reader nothing
     * about the source. Skipped when recursing; still listed at depth 1, where the
     * caller asked about that directory specifically.
     */
    private static final Set<String> IGNORED = Set.of(".git", "build", ".gradle");

    /**
     * Two, not one: a collapsed package chain occupies the first level, so a depth of
     * one would show src/test/java/com/example/agent/controller/ and none of the files
     * in it — the answer the caller actually wanted.
     */
    private static final int DEFAULT_DEPTH = 2;
    private static final int MAX_DEPTH = 12;
    private static final int DEFAULT_MAX_ENTRIES = 500;

    private final Path workspace;

    public ListDirTool(Path agentWorkspace) {
        this.workspace = agentWorkspace;
    }

    @Override public String name() { return "list_dir"; }

    @Override public String description() {
        return "List the contents of a directory. Directories are marked with a trailing '/'. "
                + "A run of directories with a single child each is collapsed onto one line "
                + "(src/test/java/com/example/agent/), so a deep package tree is shown in full "
                + "without extra calls. Two levels are shown by default; pass 'depth' for more. "
                + ".git, build and .gradle are skipped when recursing.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory path (defaults to workspace root)."),
                        "depth", Map.of(
                                "type", "integer",
                                "description", "How many levels to descend (default "
                                        + DEFAULT_DEPTH + ", maximum " + MAX_DEPTH + "). A collapsed "
                                        + "single-child chain counts as one level."),
                        "max_entries", Map.of(
                                "type", "integer",
                                "description", "Stop after this many entries (default "
                                        + DEFAULT_MAX_ENTRIES + ").")
                ),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        try {
            Path dir = args.get("path") != null && !((String) args.get("path")).isBlank()
                    ? WorkspacePath.resolve(workspace, (String) args.get("path"))
                    : workspace;
            if (!Files.isDirectory(dir)) return ToolResult.error(callId, "Not a directory: " + dir);

            int depth = clamp(intArg(args, "depth", DEFAULT_DEPTH), 1, MAX_DEPTH);
            int maxEntries = clamp(intArg(args, "max_entries", DEFAULT_MAX_ENTRIES), 1, 10_000);

            StringBuilder out = new StringBuilder();
            out.append(WorkspacePath.displayRelative(workspace, dir)).append("/\n");
            Counter counter = new Counter(maxEntries);
            walk(dir, depth, 0, out, counter);

            if (counter.hitCap) {
                out.append("... [listing truncated at ").append(maxEntries)
                        .append(" entries; narrow 'path' or lower 'depth' to see the rest]\n");
            }
            return ToolResult.ok(callId, out.toString());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    /**
     * Depth-first listing, indented one level per directory. Entries are emitted as
     * they are visited so the cap can stop the walk rather than truncating a
     * fully-built (and possibly enormous) string afterwards.
     */
    private void walk(Path dir, int maxDepth, int level, StringBuilder out, Counter counter)
            throws IOException {
        if (level >= maxDepth || counter.hitCap) return;

        List<Path> children;
        try (Stream<Path> s = Files.list(dir)) {
            children = s.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            // An unreadable subdirectory should not abort the whole listing.
            out.append("  ".repeat(level + 1)).append("[unreadable: ")
                    .append(dir.getFileName()).append("]\n");
            return;
        }

        for (Path p : children) {
            if (!counter.take()) return;

            // Never follow a link: resolving it could leave the workspace, and the
            // sandbox guarantee is that no tool reads outside it.
            boolean isLink = Files.isSymbolicLink(p);
            boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);

            out.append("  ".repeat(level + 1)).append(p.getFileName());
            if (isDir) out.append('/');
            if (isLink) out.append(" -> [symlink, not followed]");

            if (!isDir || isLink || IGNORED.contains(p.getFileName().toString())) {
                out.append('\n');
                continue;
            }

            // Collapse a chain of single-child directories onto one line, the way an
            // IDE collapses Java packages. src/test/java/com/example/agent is six
            // levels holding no information, and a model exploring it one level per
            // call burns six turns to learn nothing. A collapsed chain costs no depth
            // because it is displayed as a single entry.
            Path deepest = p;
            while (true) {
                Path onlyChild = soleSubdirectory(deepest);
                if (onlyChild == null) break;
                out.append(onlyChild.getFileName()).append('/');
                deepest = onlyChild;
            }
            out.append('\n');

            walk(deepest, maxDepth, level + 1, out, counter);
        }
    }


    /**
     * @return the single subdirectory of {@code dir} when that is all it contains,
     *         otherwise null. Used to collapse package chains; a directory holding a
     *         file, several entries, or a symlink is never collapsed.
     */
    private Path soleSubdirectory(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> entries = s.limit(2).toList();
            if (entries.size() != 1) return null;
            Path only = entries.get(0);
            if (Files.isSymbolicLink(only)) return null;
            if (!Files.isDirectory(only, LinkOption.NOFOLLOW_LINKS)) return null;
            if (IGNORED.contains(only.getFileName().toString())) return null;
            return only;
        } catch (IOException e) {
            return null;
        }
    }

    private int intArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Bounds the walk so a huge tree cannot produce an unbounded listing. */
    private static final class Counter {
        private final int max;
        private int seen;
        private boolean hitCap;

        Counter(int max) { this.max = max; }

        /** @return false once the cap is reached, at which point the walk should stop. */
        boolean take() {
            if (seen >= max) {
                hitCap = true;
                return false;
            }
            seen++;
            return true;
        }
    }
}
