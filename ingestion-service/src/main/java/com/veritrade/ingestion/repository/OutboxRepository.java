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

    /** Unpublished events, oldest first; the id breaks ties so the order is stable. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit limit);

    /** Returns the number of rows changed: 1, or 0 when the event is unknown or already published. */
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent o set o.publishedAt = :publishedAt where o.id = :id and o.publishedAt is null")
    int markPublished(UUID id, Instant publishedAt);
}
