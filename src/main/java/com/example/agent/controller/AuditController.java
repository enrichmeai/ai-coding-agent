package com.example.agent.controller;

import com.example.agent.service.AgentService;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for audit logs (tool calls and LLM calls).
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AgentService agent;
    private final ObjectMapper mapper;
    private AuditEventRepository auditRepo;

    public AuditController(AgentService agent, ObjectMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    @Autowired(required = false)
    public void setAuditEventRepository(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    /**
     * GET /api/sessions/{id}/audit
     *
     * Returns audit events for a session, owner-scoped via AgentService.requireSession.
     * If audit is not available (no repository), returns an empty list.
     *
     * Response: [ { "timestamp": "...", "eventType": "tool_call|llm_call", "detail": {...} }, ... ]
     */
    @GetMapping("/sessions/{id}/audit")
    public List<Map<String, Object>> getSessionAudit(@PathVariable String id) {
        // Verify ownership via requireSession (throws SessionNotFoundException on cross-user access)
        agent.requireSession(id);

        // If audit not available, return empty list
        if (auditRepo == null) {
            return new ArrayList<>();
        }

        // Fetch events for this session, ordered by timestamp ascending
        List<AuditEventEntity> events = auditRepo.findBySessionIdOrderByTimestampAsc(id);

        // Transform to response DTO
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditEventEntity event : events) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("timestamp", event.getTimestamp());
            dto.put("eventType", event.getEventType());

            // Parse detailJson back to a Map
            try {
                Object detail = mapper.readValue(event.getDetailJson(), Object.class);
                dto.put("detail", detail);
            } catch (Exception e) {
                log.warn("Failed to parse detail JSON for audit event {}", event.getId(), e);
                dto.put("detail", event.getDetailJson());
            }

            result.add(dto);
        }
        return result;
    }
}
