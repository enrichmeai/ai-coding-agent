package com.example.agent.tools;

import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class ListDirToolTest {

    @TempDir Path workspace;
    private ListDirTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new ListDirTool(workspace);
        // A Java-shaped tree: the case that cost six agent turns before depth existed.
        Files.createDirectories(workspace.resolve("src/test/java/com/example/agent/controller"));
        Files.writeString(workspace.resolve("src/test/java/com/example/agent/controller/AgentControllerIT.java"), "class X {}");
        Files.writeString(workspace.resolve("README.md"), "readme");
    }

    @Test
    void collapsesASingleChildChainSoAPackageTreeCostsOneCall() {
        // The exact shape that cost six agent turns: every level has one child until
        // 'controller'. At the default depth the whole chain must still be visible.
        ToolResult r = tool.execute("c1", Map.of("path", "src/test"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content())
                .contains("java/com/example/agent/controller/")
                .contains("AgentControllerIT.java");
    }

    @Test
    void doesNotCollapseADirectoryThatHasMoreThanOneEntry() throws IOException {
        Files.createDirectories(workspace.resolve("multi/alpha"));
        Files.createDirectories(workspace.resolve("multi/beta"));

        ToolResult r = tool.execute("c1b", Map.of("path", "multi"));

        assertThat(r.content()).contains("alpha/").contains("beta/");
        assertThat(r.content()).doesNotContain("alpha/beta");
    }

    @Test
    void doesNotCollapseADirectoryHoldingAFile() throws IOException {
        Files.createDirectories(workspace.resolve("withfile/sub"));
        Files.writeString(workspace.resolve("withfile/note.txt"), "x");

        ToolResult r = tool.execute("c1c", Map.of("path", "withfile"));

        assertThat(r.content()).contains("note.txt").contains("sub/");
    }

    @Test
    void stillStopsAtTheRequestedDepthOnceTheTreeBranches() {
        // 'agent' branches, so its children are one real level below.
        ToolResult r = tool.execute("c1d", Map.of("path", "src", "depth", 1));

        assertThat(r.content()).contains("test/java/com/example/agent/controller/");
    }

    @Test
    void depthDescendsSeveralLevelsInOneCall() {
        ToolResult r = tool.execute("c2", Map.of("path", "src/test/java", "depth", 5));

        assertThat(r.isError()).isFalse();
        assertThat(r.content())
                .contains("com/")
                .contains("example/")
                .contains("agent/")
                .contains("controller/")
                .contains("AgentControllerIT.java");
    }

    @Test
    void acceptsDepthAsAStringSinceModelsOftenSendOne() {
        ToolResult r = tool.execute("c3", Map.of("path", "src/test/java", "depth", "5"));

        assertThat(r.content()).contains("AgentControllerIT.java");
    }

    @Test
    void skipsNoisyDirectoriesWhenRecursing() throws IOException {
        Files.createDirectories(workspace.resolve("build/classes/deep"));
        Files.writeString(workspace.resolve("build/classes/deep/Generated.class"), "x");
        Files.createDirectories(workspace.resolve(".git/objects"));
        Files.writeString(workspace.resolve(".git/objects/abc"), "x");

        ToolResult r = tool.execute("c4", Map.of("depth", 6));

        // The directories themselves are still visible; their contents are not.
        assertThat(r.content()).contains("build/").contains(".git/");
        assertThat(r.content()).doesNotContain("Generated.class");
        assertThat(r.content()).doesNotContain("abc");
    }

    @Test
    void stopsAtMaxEntriesAndSaysSo() throws IOException {
        for (int i = 0; i < 40; i++) {
            Files.writeString(workspace.resolve("file" + i + ".txt"), "x");
        }

        ToolResult r = tool.execute("c5", Map.of("max_entries", 5));

        assertThat(r.content()).contains("listing truncated at 5 entries");
    }

    @Test
    void doesNotFollowASymlinkPointingOutsideTheWorkspace() throws IOException {
        Path outside = Files.createTempDirectory("outside-workspace");
        Files.writeString(outside.resolve("secret.txt"), "should never be listed");
        try {
            Files.createSymbolicLink(workspace.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeThat(false).as("symlinks not supported here").isTrue();
        }

        ToolResult r = tool.execute("c6", Map.of("depth", 5));

        assertThat(r.content()).contains("escape");
        assertThat(r.content()).contains("symlink, not followed");
        // The guarantee: nothing outside the workspace is ever read.
        assertThat(r.content()).doesNotContain("secret.txt");
    }

    @Test
    void rejectsTraversalOutsideTheWorkspace() {
        ToolResult r = tool.execute("c7", Map.of("path", "../.."));

        assertThat(r.isError()).isTrue();
    }

    @Test
    void reportsWhenThePathIsNotADirectory() {
        ToolResult r = tool.execute("c8", Map.of("path", "README.md"));

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Not a directory");
    }
}
