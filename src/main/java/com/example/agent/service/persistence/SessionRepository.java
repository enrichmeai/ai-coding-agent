package com.example.agent.service.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    List<SessionEntity> findAllByUserId(String userId);

    @Query("""
            SELECT DISTINCT s FROM SessionEntity s
            LEFT JOIN MessageEntity m ON m.sessionId = s.id
            WHERE s.userId = :owner
              AND (LOWER(s.title) LIKE :q OR LOWER(cast(m.payloadJson as string)) LIKE :q)
            ORDER BY s.createdAt DESC
            """)
    List<SessionEntity> searchOwned(@Param("owner") String owner,
                                    @Param("q") String q,
                                    Pageable pageable);
}
