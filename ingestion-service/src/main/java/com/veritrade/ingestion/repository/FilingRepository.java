package com.veritrade.ingestion.repository;

import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingState;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FilingRepository extends JpaRepository<Filing, UUID> {

    /** Newest first; the id breaks ties so the order is stable. Does not load the content. */
    List<FilingView> findAllByOrderBySubmittedAtDescIdDesc(Limit limit);

    /** Status of one filing without loading its content. */
    Optional<FilingView> findViewById(UUID id);

    /** Status and version of one filing without loading its content. */
    Optional<FilingState> findStateById(UUID id);

    /**
     * Writes a status change without loading the content. It applies only when the filing still has
     * {@code version} (the same optimistic-lock check as {@code @Version}) and increments the version.
     * Returns the number of rows changed: 1, or 0 when the filing changed in between.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Filing f
            set f.status = :status, f.failureReason = :failureReason, f.updatedAt = :updatedAt, f.version = f.version + 1
            where f.id = :id and f.version = :version""")
    int changeStatus(UUID id, Long version, FilingStatus status, String failureReason, Instant updatedAt);
}
