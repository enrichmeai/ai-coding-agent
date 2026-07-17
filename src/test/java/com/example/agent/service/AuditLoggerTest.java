package com.example.agent.service;

import com.example.agent.config.CurrentUser;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AuditLogger.
 * Tests the component without Spring context; @Async doesn't apply in unit tests.
 */
public class AuditLoggerTest {

    private AuditLogger auditLogger;
    private MockAuditEventRepository mockRepo;
    private CurrentUser mockCurrentUser;
    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mockRepo = new MockAuditEventRepository();
        mockCurrentUser = new MockCurrentUser("alice");
        mapper = new ObjectMapper();
        auditLogger = new AuditLogger(mockRepo, mapper, mockCurrentUser);
    }

    @Test
    public void testToolCall_Success() {
        // Act
        auditLogger.toolCall("session-1", "read_file", Map.of("path", "/etc/passwd"), true, 1024);

        // Assert
        assertEquals(1, mockRepo.saved.size());
        AuditEventEntity event = mockRepo.saved.get(0);
        assertEquals("alice", event.getUserId());
        assertEquals("session-1", event.getSessionId());
        assertEquals("tool_call", event.getEventType());
        assertTrue(event.getDetailJson().contains("read_file"));
        assertTrue(event.getDetailJson().contains("1024"));
        assertTrue(event.getDetailJson().contains("true"));
        assertNotNull(event.getTimestamp());
    }

    @Test
    public void testToolCall_Failure() {
        // Act
        auditLogger.toolCall("session-1", "write_file", Map.of("path", "/restricted"), false, 0);

        // Assert
        assertEquals(1, mockRepo.saved.size());
        AuditEventEntity event = mockRepo.saved.get(0);
        assertEquals("tool_call", event.getEventType());
        assertTrue(event.getDetailJson().contains("false"));
    }

    @Test
    public void testLlmCall_Success() {
        // Act
        auditLogger.llmCall("session-1", "anthropic", 1000, 500, true);

        // Assert
        assertEquals(1, mockRepo.saved.size());
        AuditEventEntity event = mockRepo.saved.get(0);
        assertEquals("alice", event.getUserId());
        assertEquals("session-1", event.getSessionId());
        assertEquals("llm_call", event.getEventType());
        assertTrue(event.getDetailJson().contains("anthropic"));
        assertTrue(event.getDetailJson().contains("1000"));
        assertTrue(event.getDetailJson().contains("500"));
    }

    @Test
    public void testLlmCall_Failure() {
        // Act
        auditLogger.llmCall("session-1", "openai", 0, 0, false);

        // Assert
        assertEquals(1, mockRepo.saved.size());
        AuditEventEntity event = mockRepo.saved.get(0);
        assertEquals("llm_call", event.getEventType());
        assertTrue(event.getDetailJson().contains("false"));
    }

    @Test
    public void testNoRepository_NullRepo() {
        // Arrange: AuditLogger with null repo
        AuditLogger auditLoggerNoRepo = new AuditLogger(null, mapper, mockCurrentUser);

        // Act: should not throw
        auditLoggerNoRepo.toolCall("session-1", "test_tool", Map.of(), true, 0);
        auditLoggerNoRepo.llmCall("session-1", "anthropic", 0, 0, true);

        // Assert: nothing saved
        assertEquals(0, mockRepo.saved.size());
    }

    @Test
    public void testSessionIdNull() {
        // Act
        auditLogger.toolCall(null, "bash", Map.of("cmd", "echo hello"), true, 100);

        // Assert
        assertEquals(1, mockRepo.saved.size());
        AuditEventEntity event = mockRepo.saved.get(0);
        assertNull(event.getSessionId());
        assertEquals("tool_call", event.getEventType());
    }

    /**
     * Mock repository for testing. Implements only the methods the AuditLogger actually uses
     * and returns empty results for the rest of the JpaRepository surface.
     */
    private static class MockAuditEventRepository implements AuditEventRepository {
        private final List<AuditEventEntity> saved = new ArrayList<>();

        @Override
        public <S extends AuditEventEntity> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public <S extends AuditEventEntity> List<S> saveAll(Iterable<S> entities) {
            return java.util.Collections.emptyList();
        }

        @Override
        public java.util.Optional<AuditEventEntity> findById(Long aLong) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean existsById(Long aLong) {
            return false;
        }

        @Override
        public List<AuditEventEntity> findAll() {
            return java.util.Collections.emptyList();
        }

        @Override
        public List<AuditEventEntity> findAllById(Iterable<Long> longs) {
            return java.util.Collections.emptyList();
        }

        @Override
        public long count() {
            return 0;
        }

        @Override
        public void deleteById(Long aLong) {}

        @Override
        public void delete(AuditEventEntity entity) {}

        @Override
        public void deleteAllById(Iterable<? extends Long> longs) {}

        @Override
        public void deleteAll(Iterable<? extends AuditEventEntity> entities) {}

        @Override
        public void deleteAll() {}

        @Override
        public void flush() {}

        @Override
        public <S extends AuditEventEntity> S saveAndFlush(S entity) {
            return save(entity);
        }

        @Override
        public <S extends AuditEventEntity> List<S> saveAllAndFlush(Iterable<S> entities) {
            return java.util.Collections.emptyList();
        }

        @Override
        public void deleteAllInBatch(Iterable<AuditEventEntity> entities) {}

        @Override
        public void deleteAllByIdInBatch(Iterable<Long> longs) {}

        @Override
        public void deleteAllInBatch() {}

        @Override
        public AuditEventEntity getById(Long aLong) {
            return null;
        }

        @Override
        public AuditEventEntity getReferenceById(Long aLong) {
            return null;
        }

        @Override
        public AuditEventEntity getOne(Long aLong) {
            return null;
        }

        @Override
        public <S extends AuditEventEntity> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) {
            return java.util.Optional.empty();
        }

        @Override
        public <S extends AuditEventEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) {
            return java.util.Collections.emptyList();
        }

        @Override
        public <S extends AuditEventEntity> List<S> findAll(org.springframework.data.domain.Example<S> example,
                                                             org.springframework.data.domain.Sort sort) {
            return java.util.Collections.emptyList();
        }

        @Override
        public <S extends AuditEventEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example,
                                                                                              org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override
        public <S extends AuditEventEntity> long count(org.springframework.data.domain.Example<S> example) {
            return 0;
        }

        @Override
        public <S extends AuditEventEntity> boolean exists(org.springframework.data.domain.Example<S> example) {
            return false;
        }

        @Override
        public <S extends AuditEventEntity, R> R findBy(org.springframework.data.domain.Example<S> example,
                                                         java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }

        @Override
        public List<AuditEventEntity> findAll(org.springframework.data.domain.Sort sort) {
            return java.util.Collections.emptyList();
        }

        @Override
        public org.springframework.data.domain.Page<AuditEventEntity> findAll(org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override
        public List<AuditEventEntity> findBySessionIdOrderByTimestampAsc(String sessionId) {
            return java.util.Collections.emptyList();
        }

        @Override
        public List<AuditEventEntity> findByUserIdOrderByTimestampDesc(String userId,
                                                                        org.springframework.data.domain.Pageable pageable) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Mock CurrentUser for testing.
     */
    private static class MockCurrentUser extends CurrentUser {
        private final String name;

        MockCurrentUser(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }
}
