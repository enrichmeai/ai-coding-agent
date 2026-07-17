package com.example.agent.service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderByPositionAsc(String sessionId);

    @Transactional
    void deleteBySessionId(String sessionId);
}
