package com.example.agent.controller;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.service.AgentService;
import com.example.agent.service.SessionStore;
import com.example.agent.transcript.TranscriptReader;
import com.example.agent.transcript.TranscriptWriter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Import / export sessions as Markdown transcripts.
 *
 * Export honours session ownership via {@link AgentService#requireSession(String)};
 * cross-user lookups surface as 404 (never 403) — same shape as
 * {@link AgentController#get(String)}.
 *
 * Import always creates a brand new session owned by the current user; the
 * messages are persisted directly (no LLM replay).
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionTranscriptController {

    private final AgentService agent;
    private final SessionStore sessions;
    private final TranscriptWriter writer;
    private final TranscriptReader reader;

    public SessionTranscriptController(AgentService agent,
                                       SessionStore sessions,
                                       TranscriptWriter writer,
                                       TranscriptReader reader) {
        this.agent = agent;
        this.sessions = sessions;
        this.writer = writer;
        this.reader = reader;
    }

    @GetMapping(path = "/{id}/export.md", produces = "text/markdown;charset=UTF-8")
    public String export(@PathVariable String id) {
        Session s = agent.requireSession(id);
        return writer.write(s);
    }

    @PostMapping(path = "/import",
                 consumes = "text/markdown",
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> importTranscript(@RequestBody String body) {
        TranscriptReader.Parsed parsed;
        try {
            parsed = reader.parse(body);
        } catch (IllegalArgumentException ex) {
            throw ex; // ErrorAdvice → 400
        }

        Session s = sessions.create();
        if (parsed.title() != null && !parsed.title().isBlank()) {
            s.setTitle(parsed.title());
            sessions.update(s);
        }
        for (ChatMessage m : parsed.messages()) {
            s.add(m);
            sessions.appendMessage(s, m);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", s.getId(), "title", s.getTitle()));
    }
}
