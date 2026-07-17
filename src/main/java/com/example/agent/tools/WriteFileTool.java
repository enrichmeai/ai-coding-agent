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
public class WriteFileTool implements Tool {

    private final Path workspace;
    private final AgentProperties.File cfg;

    public WriteFileTool(Path agentWorkspace, AgentProperties props) {
        this.workspace = agentWorkspace;
        this.cfg = props.getTools().getFile();
    }

    @Override public String name() { return "write_file"; }

    @Override public String description() {
        return "Create or overwrite a text file in the workspace with the given content. Parent directories are created automatically.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path",    Map.of("type", "string", "description", "Destination path (relative to workspace)."),
                        "content", Map.of("type", "string", "description", "Full file content to write.")
                ),
                "required", List.of("path", "content")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) {
        try {
            if (!cfg.isEnabled()) return ToolResult.error(callId, "File tool is disabled.");

            Path file = WorkspacePath.resolve(workspace, (String) args.get("path"));
            String content = (String) args.getOrDefault("content", "");
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);

            return ToolResult.ok(callId,
                    "Wrote " + content.length() + " chars to " +
                            WorkspacePath.displayRelative(workspace, file));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }
}
