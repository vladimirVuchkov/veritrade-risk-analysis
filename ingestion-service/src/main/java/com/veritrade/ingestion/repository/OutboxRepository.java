package com.veritrade.ingestion.repository;

import com.veritrade.ingestion.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Unpublished events that are not parked, oldest first; the id breaks ties so the order is stable. */
    List<OutboxEvent> findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit limit);

    /** Returns the number of rows changed: 1, or 0 when the event is unknown or already published. */
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent o set o.publishedAt = :publishedAt where o.id = :id and o.publishedAt is null")
    int markPublished(UUID id, Instant publishedAt);

    /** Counts one failed attempt of a pending row and keeps its error. Returns the number of rows changed. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o set o.attempts = o.attempts + 1, o.lastError = :error
            where o.id = :id and o.publishedAt is null and o.parkedAt is null""")
    int recordFailedAttempt(UUID id, String error);

    /** Parks a pending row that has used up its attempts. Returns 1 when it was parked now, otherwise 0. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o set o.parkedAt = :parkedAt
            where o.id = :id and o.attempts >= :maxAttempts and o.publishedAt is null and o.parkedAt is null""")
    int parkIfExhausted(UUID id, int maxAttempts, Instant parkedAt);
}
