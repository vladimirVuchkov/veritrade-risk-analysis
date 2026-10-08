package com.veritrade.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.ingestion.config.ClockConfig;
import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.StatusChange;
import com.veritrade.ingestion.repository.FilingRepository;
import com.veritrade.ingestion.support.SqlRecorder;
import com.veritrade.ingestion.support.TestProperties;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Review W3-07: applying a status event must not read the filing content (up to 2 MB), on the real
 * Flyway schema and Hibernate. Every SQL statement Hibernate prepares is recorded and checked.
 */
@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:filing-status-sql;DB_CLOSE_DELAY=-1", SqlRecorder.PROPERTY})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FilingStatusService.class, ClockConfig.class})
class FilingStatusSqlTest {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");

    @Autowired
    private FilingStatusService service;

    @Autowired
    private FilingRepository filings;

    @Autowired
    private EntityManager entityManager;

    private UUID filingId;

    @BeforeEach
    void storeALargeFiling() {
        filingId = filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K",
                "x".repeat(TestProperties.MAX_CONTENT_BYTES), T0)).id();
        entityManager.flush();
        entityManager.clear();
        SqlRecorder.clear();
    }

    @Test
    void appliesEveryKindOfStatusEventWithoutReadingTheContent() {
        assertThat(service.apply(update(FilingStatus.ANALYZING, null))).isEqualTo(StatusChange.APPLIED);
        assertThat(service.apply(update(FilingStatus.ANALYZING, null))).isEqualTo(StatusChange.DUPLICATE);
        assertThat(service.apply(update(FilingStatus.FAILED, "rule engine error"))).isEqualTo(StatusChange.APPLIED);
        assertThat(service.apply(update(FilingStatus.COMPLETED, null))).isEqualTo(StatusChange.REJECTED);
        entityManager.flush();

        assertThat(SqlRecorder.statements()).isNotEmpty()
                .allSatisfy(sql -> assertThat(sql.toLowerCase()).doesNotContain("content"));
        assertThat(SqlRecorder.statements()).anySatisfy(sql -> assertThat(sql.toLowerCase()).startsWith("update"));
    }

    @Test
    void storesTheChangeAndIncrementsTheOptimisticLockVersion() {
        service.apply(update(FilingStatus.ANALYZING, null));
        service.apply(update(FilingStatus.FAILED, "rule engine error"));
        entityManager.flush();
        entityManager.clear();

        final Filing stored = filings.findById(filingId).orElseThrow();
        assertThat(stored.status()).isEqualTo(FilingStatus.FAILED);
        assertThat(stored.failureReason()).isEqualTo("rule engine error");
        assertThat(stored.content()).hasSize(TestProperties.MAX_CONTENT_BYTES);
        assertThat(filings.findStateById(filingId).orElseThrow().version()).isEqualTo(2L);
    }

    private StatusUpdate update(final FilingStatus target, final String reason) {
        return new StatusUpdate(UUID.randomUUID(), filingId, target, reason);
    }
}
