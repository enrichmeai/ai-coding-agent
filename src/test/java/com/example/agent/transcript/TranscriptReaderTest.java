package com.example.agent.transcript;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranscriptReaderTest {

    @Test
    void roundTripsThroughWriterAndReader() {
        ObjectMapper mapper = new ObjectMapper();
        TranscriptWriter writer = new TranscriptWriter(mapper);
        TranscriptReader reader = new TranscriptReader(mapper);

        Session s = new TranscriptWriterTest.RecordingSession(
                "sess-abc", Instant.parse("2026-05-23T12:00:00Z"));
        s.setTitle("Round trip");

        s.add(ChatMessage.user("Hi"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("x", 1);
        ToolCall tc = new ToolCall("c1", "do_it", args);
        s.add(ChatMessage.assistant("Working on it", List.of(tc)));
        s.add(ChatMessage.tool(List.of(ToolResult.ok("c1", "done"))));

        String md = writer.write(s);
        TranscriptReader.Parsed parsed = reader.parse(md);

        assertEquals("Round trip", parsed.title());
        assertEquals(3, parsed.messages().size());

        // USER message — compare role + text (timestamps drift on import).
        ChatMessage origUser = s.getHistory().get(0);
        ChatMessage roundUser = parsed.messages().get(0);
        assertEquals(origUser.role(), roundUser.role());
        assertEquals(origUser.text(), roundUser.text());
        assertEquals(List.of(), roundUser.toolCalls());
        assertEquals(List.of(), roundUser.toolResults());

        // ASSISTANT message with tool call.
        ChatMessage origAsst = s.getHistory().get(1);
        ChatMessage roundAsst = parsed.messages().get(1);
        assertEquals(origAsst.role(), roundAsst.role());
        assertEquals(origAsst.text(), roundAsst.text());
        assertEquals(1, roundAsst.toolCalls().size());
        ToolCall got = roundAsst.toolCalls().get(0);
        assertEquals("c1", got.id());
        assertEquals("do_it", got.name());
        // Jackson reads `1` as Integer; original Map also held Integer.
        assertEquals(1, ((Number) got.arguments().get("x")).intValue());

        // TOOL message with result.
        ChatMessage origTool = s.getHistory().get(2);
        ChatMessage roundTool = parsed.messages().get(2);
        assertEquals(origTool.role(), roundTool.role());
        assertEquals(1, roundTool.toolResults().size());
        ToolResult tr = roundTool.toolResults().get(0);
        assertEquals("c1", tr.callId());
        assertEquals("done", tr.content());
        assertEquals(false, tr.isError());

        // Per-message timestamp is regenerated on import — just assert it's present.
        for (ChatMessage m : parsed.messages()) {
            assertNotNull(m.timestamp());
        }
    }
}
