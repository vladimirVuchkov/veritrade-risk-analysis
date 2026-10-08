package com.veritrade.ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.domain.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Limit;

/**
 * Runs against the Flyway schema (Hibernate validates the mapping against it). The pooled data source
 * of the application is kept instead of the embedded one: with H2 2.4.240 a CHECK constraint fails once
 * the connection that created it is closed, which the embedded data source does after the migration.
 */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:repository;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");

    @Autowired
    private FilingRepository filings;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private EntityManager entityManager;

    @Test
    void listsNewestFirstAndRespectsTheLimit() {
        Filing oldest = save(T0);
        Filing middle = save(T0.plusSeconds(1));
        Filing newest = save(T0.plusSeconds(2));

        List<FilingView> all = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(10));
        List<FilingView> two = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(2));

        assertThat(all).extracting(FilingView::id).containsExactly(newest.id(), middle.id(), oldest.id());
        assertThat(two).extracting(FilingView::id).containsExactly(newest.id(), middle.id());
    }

    @Test
    void breaksTimestampTiesByIdSoTheOrderIsStable() {
        Filing first = save(T0);
        Filing second = save(T0);

        List<UUID> listed = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(10)).stream().map(FilingView::id).toList();

        assertThat(listed).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(listed.getFirst().toString()).isGreaterThan(listed.getLast().toString());
    }

    @Test
    void readsTheViewOfOneFiling() {
        Filing filing = save(T0);
        filing.changeStatus(FilingStatus.FAILED, "rule engine error", T0.plusSeconds(5));
        flushAndClear();

        assertThat(filings.findViewById(filing.id())).contains(
                new FilingView(filing.id(), "Acme", "10-K", FilingStatus.FAILED, T0, "rule engine error"));
        assertThat(filings.findViewById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void persistsMicrosecondTimestampsAndLongContent() {
        Instant precise = Instant.parse("2026-10-07T12:00:00.123456Z");
        Filing filing = filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K", "x".repeat(2_097_152), precise));
        flushAndClear();

        Filing loaded = filings.findById(filing.id()).orElseThrow();
        assertThat(loaded.submittedAt()).isEqualTo(precise);
        assertThat(loaded.content()).hasSize(2_097_152);
    }

    @Test
    void columnsCountUtf16UnitsSoFourByteCharactersTakeTwo() {
        String company = "\uD83D\uDE00".repeat(100);
        String title = "\uD83D\uDE00".repeat(150);
        Filing filing = filings.save(Filing.submit(UUID.randomUUID(), company, title, "text", T0));
        filing.changeStatus(FilingStatus.FAILED, "\uD83D\uDE00".repeat(500), T0);
        flushAndClear();

        Filing loaded = filings.findById(filing.id()).orElseThrow();
        assertThat(loaded.companyName()).isEqualTo(company).hasSize(200);
        assertThat(loaded.title()).isEqualTo(title).hasSize(300);
        assertThat(loaded.failureReason()).hasSize(1000);
    }

    @Test
    void returnsUnpublishedEventsOldestFirst() {
        OutboxEvent newer = saveEvent(T0.plusSeconds(2));
        OutboxEvent older = saveEvent(T0);
        OutboxEvent published = saveEvent(T0.plusSeconds(1));
        outbox.markPublished(published.id(), T0.plusSeconds(3));

        List<OutboxEvent> batch = outbox.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(10));

        assertThat(batch).extracting(OutboxEvent::id).containsExactly(older.id(), newer.id());
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(1)))
                .extracting(OutboxEvent::id).containsExactly(older.id());
    }

    @Test
    void marksAnEventPublishedOnlyOnce() {
        OutboxEvent event = saveEvent(T0);

        int first = outbox.markPublished(event.id(), T0.plusSeconds(1));
        int second = outbox.markPublished(event.id(), T0.plusSeconds(2));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(outbox.findById(event.id()).orElseThrow().publishedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(outbox.markPublished(UUID.randomUUID(), T0)).isZero();
    }

    private Filing save(Instant submittedAt) {
        Filing filing = filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", submittedAt));
        filings.flush();
        return filing;
    }

    private OutboxEvent saveEvent(Instant createdAt) {
        OutboxEvent event = outbox.save(new OutboxEvent(UUID.randomUUID(), "filing.submitted", "corr", "{}", createdAt));
        outbox.flush();
        return event;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
