package com.veritrade.ingestion.repository;

import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilingRepository extends JpaRepository<Filing, UUID> {

    /** Newest first; the id breaks ties so the order is stable. Does not load the content. */
    List<FilingView> findAllByOrderBySubmittedAtDescIdDesc(Limit limit);

    /** Status of one filing without loading its content. */
    Optional<FilingView> findViewById(UUID id);
}
