package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir Path workspace;
    private AgentProperties props;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setWorkspace(workspace.toString());
    }

    @Test
    void writeReadEditRoundTrip() throws Exception {
        WriteFileTool write = new WriteFileTool(workspace, props);
        ReadFileTool  read  = new ReadFileTool(workspace, props);
        EditFileTool  edit  = new EditFileTool(workspace, props);

        ToolResult w = write.execute("c1",
                Map.of("path", "hello.txt", "content", "Hello, world\nSecond line"), ToolContext.anonymous());
        assertFalse(w.isError(), w.content());

        ToolResult r = read.execute("c2", Map.of("path", "hello.txt"), ToolContext.anonymous());
        assertFalse(r.isError());
        assertTrue(r.content().contains("Hello, world"));

        ToolResult e = edit.execute("c3",
                Map.of("path", "hello.txt", "old_string", "world", "new_string", "agent"), ToolContext.anonymous());
        assertFalse(e.isError(), e.content());

        String after = Files.readString(workspace.resolve("hello.txt"));
        assertTrue(after.contains("Hello, agent"));
    }

    @Test
    void pathTraversalIsBlocked() {
        WriteFileTool write = new WriteFileTool(workspace, props);
        ToolResult r = write.execute("c1", Map.of("path", "../escape.txt", "content", "x"), ToolContext.anonymous());
        assertTrue(r.isError(), "expected path traversal to fail: " + r.content());
    }

    @Test
    void editFailsWhenOldStringAmbiguous() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "foo\nfoo\n");
        EditFileTool edit = new EditFileTool(workspace, props);
        ToolResult r = edit.execute("c1",
                Map.of("path", "a.txt", "old_string", "foo", "new_string", "bar"), ToolContext.anonymous());
        assertTrue(r.isError());
        assertTrue(r.content().contains("appears 2 times"));
    }
}
