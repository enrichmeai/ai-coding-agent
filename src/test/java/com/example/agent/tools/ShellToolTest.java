package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void expandedBlockListRejectsCurl(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of(
                "^\\s*rm\\s+-rf\\s+/",
                "^\\s*:\\(\\)\\{",
                "mkfs",
                "dd\\s+if=",
                "(?i)\\bcurl\\b",
                "(?i)\\bwget\\b",
                "(?i)\\bnc\\b",
                "(?i)\\bncat\\b",
                "(?i)\\bssh\\b",
                "(?i)\\bscp\\b",
                "(?i)\\brsync\\b",
                "(?i)\\bftp\\b",
                "(?i)\\bnohup\\b",
                "(?i)/etc/shadow",
                "(?i)~/\\.ssh"
        ));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-1", Map.of("command", "curl https://evil.com"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("blocked by policy"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void expandedBlockListRejectsSsh(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of(
                "^\\s*rm\\s+-rf\\s+/",
                "^\\s*:\\(\\)\\{",
                "mkfs",
                "dd\\s+if=",
                "(?i)\\bcurl\\b",
                "(?i)\\bwget\\b",
                "(?i)\\bnc\\b",
                "(?i)\\bncat\\b",
                "(?i)\\bssh\\b",
                "(?i)\\bscp\\b",
                "(?i)\\brsync\\b",
                "(?i)\\bftp\\b",
                "(?i)\\bnohup\\b",
                "(?i)/etc/shadow",
                "(?i)~/\\.ssh"
        ));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-2", Map.of("command", "ssh user@host"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("blocked by policy"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListEmptyIsPermissive(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of()); // Empty allow-list = permissive
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-3", Map.of("command", "ls -la"));

        assertFalse(result.isError(), "Empty allow-list should not reject 'ls -la'");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListRejectsUnlistedCommand(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of("ls", "echo"));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-4", Map.of("command", "grep foo bar.txt"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("not in allow-list"));
        assertTrue(result.content().contains("grep"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListAcceptsListedCommand(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of("ls"));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-5", Map.of("command", "ls"));

        assertFalse(result.isError(), "Allow-list should accept 'ls'");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void killSwitchDisabledReturnsError(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(false);
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-6", Map.of("command", "ls"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("disabled"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListMatchesFirstTokenOnly(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of("ls"));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        ToolResult result = tool.execute("test-call-7", Map.of("command", "ls -la /tmp"));

        assertFalse(result.isError(), "Allow-list should accept 'ls -la /tmp' (first token 'ls' is allowed)");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListHandlesPathStripping(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of("ls"));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        // /bin/ls should extract to "ls" and match the allow-list. Use /bin/ls
        // instead of /usr/bin/ls because newer macOS releases ship the binary at
        // /bin only, while Linux has it in both locations.
        ToolResult result = tool.execute("test-call-8", Map.of("command", "/bin/ls"));

        assertFalse(result.isError(), "Allow-list should accept '/bin/ls' (extracted token 'ls' is allowed)");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void allowListHandlesGradlewWithDot(@TempDir Path workspace) throws Exception {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of());
        shellCfg.setAllowedCommands(List.of("gradlew"));
        props.getTools().setShell(shellCfg);

        // Create an executable gradlew stub in the workspace so the actual
        // shell invocation succeeds — the test is asserting allow-list logic,
        // not gradle behaviour.
        Path gradlew = workspace.resolve("gradlew");
        java.nio.file.Files.writeString(gradlew, "#!/bin/sh\nexit 0\n");
        gradlew.toFile().setExecutable(true);

        ShellTool tool = new ShellTool(workspace, props);
        // ./gradlew should extract to "gradlew" and match the allow-list
        ToolResult result = tool.execute("test-call-9", Map.of("command", "./gradlew build"));

        assertFalse(result.isError(), "Allow-list should accept './gradlew build' (extracted token 'gradlew' is allowed)");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void blockListAndAllowListBothApply(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setBlockedPatterns(List.of("(?i)\\bcurl\\b"));
        shellCfg.setAllowedCommands(List.of("curl", "ls", "echo"));
        props.getTools().setShell(shellCfg);

        ShellTool tool = new ShellTool(workspace, props);
        // curl is in allow-list but blocked by block-list; block-list should win
        ToolResult result = tool.execute("test-call-10", Map.of("command", "curl https://example.com"));

        assertTrue(result.isError(), "Block-list should reject curl even if it's in allow-list");
        assertTrue(result.content().contains("blocked by policy"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void killsACommandThatKeepsPrintingPastTheTimeout(@TempDir Path workspace) {
        // Regression: output was drained to EOF before the clock was checked, so a
        // command that kept printing held the turn for its whole run and then
        // reported success. A Gradle build is exactly that shape.
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setTimeoutSeconds(1);
        props.getTools().setShell(shellCfg);
        ShellTool tool = new ShellTool(workspace, props);

        long start = System.currentTimeMillis();
        ToolResult r = tool.execute("t-slow",
                Map.of("command", "for i in $(seq 1 100); do echo line $i; sleep 0.2; done"));
        long elapsedMs = System.currentTimeMillis() - start;

        assertTrue(r.isError(), r.content());
        assertTrue(r.content().contains("timed out"), r.content());
        // Would be ~20s if the timeout were ignored.
        assertTrue(elapsedMs < 10_000, "took " + elapsedMs + "ms; timeout was not enforced");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void perCallTimeoutOverrideIsHonoured(@TempDir Path workspace) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setTimeoutSeconds(600);
        props.getTools().setShell(shellCfg);
        ShellTool tool = new ShellTool(workspace, props);

        ToolResult r = tool.execute("t-override",
                Map.of("command", "sleep 30", "timeout_seconds", 1));

        assertTrue(r.isError());
        assertTrue(r.content().contains("timed out"), r.content());
    }
}
