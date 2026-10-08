package com.veritrade.ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingState;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.domain.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsNewestFirstAndRespectsTheLimit() {
        final Filing oldest = save(T0);
        final Filing middle = save(T0.plusSeconds(1));
        final Filing newest = save(T0.plusSeconds(2));

        final List<FilingView> all = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(10));
        final List<FilingView> two = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(2));

        assertThat(all).extracting(FilingView::id).containsExactly(newest.id(), middle.id(), oldest.id());
        assertThat(two).extracting(FilingView::id).containsExactly(newest.id(), middle.id());
    }

    @Test
    void breaksTimestampTiesByIdSoTheOrderIsStable() {
        final Filing first = save(T0);
        final Filing second = save(T0);

        final List<UUID> listed = filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(10)).stream().map(FilingView::id).toList();

        assertThat(listed).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(listed.getFirst().toString()).isGreaterThan(listed.getLast().toString());
    }

    @Test
    void readsTheViewOfOneFiling() {
        final Filing filing = save(T0);
        filings.changeStatus(filing.id(), 0L, FilingStatus.FAILED, "rule engine error", T0.plusSeconds(5));
        flushAndClear();

        assertThat(filings.findViewById(filing.id())).contains(
                new FilingView(filing.id(), "Acme", "10-K", FilingStatus.FAILED, T0, "rule engine error"));
        assertThat(filings.findViewById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void readsTheStateOfOneFilingWithItsVersion() {
        final Filing filing = save(T0);

        assertThat(filings.findStateById(filing.id())).contains(new FilingState(filing.id(), FilingStatus.SUBMITTED, 0L));
        assertThat(filings.findStateById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void changesTheStatusAndIncrementsTheVersion() {
        final Filing filing = save(T0);
        flushAndClear();

        final int changed = filings.changeStatus(filing.id(), 0L, FilingStatus.FAILED, "boom", T0.plusSeconds(5));

        assertThat(changed).isEqualTo(1);
        final Filing loaded = filings.findById(filing.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(FilingStatus.FAILED);
        assertThat(loaded.failureReason()).isEqualTo("boom");
        assertThat(loaded.updatedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(loaded.content()).isEqualTo("text");
        assertThat(filings.findStateById(filing.id()).orElseThrow().version()).isEqualTo(1L);
    }

    @Test
    void doesNotChangeTheStatusWithAStaleVersion() {
        final Filing filing = save(T0);
        filings.changeStatus(filing.id(), 0L, FilingStatus.ANALYZING, null, T0.plusSeconds(1));

        final int changed = filings.changeStatus(filing.id(), 0L, FilingStatus.FAILED, "boom", T0.plusSeconds(2));

        assertThat(changed).isZero();
        assertThat(filings.findStateById(filing.id()).orElseThrow())
                .isEqualTo(new FilingState(filing.id(), FilingStatus.ANALYZING, 1L));
        assertThat(filings.changeStatus(UUID.randomUUID(), 0L, FilingStatus.FAILED, "x", T0)).isZero();
    }

    @Test
    void persistsMicrosecondTimestampsAndLongContent() {
        final Instant precise = Instant.parse("2026-10-07T12:00:00.123456Z");
        final Filing filing = filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K", "x".repeat(2_097_152), precise));
        flushAndClear();

        final Filing loaded = filings.findById(filing.id()).orElseThrow();
        assertThat(loaded.submittedAt()).isEqualTo(precise);
        assertThat(loaded.content()).hasSize(2_097_152);
    }

    @Test
    void columnsCountUtf16UnitsSoFourByteCharactersTakeTwo() {
        final String company = "\uD83D\uDE00".repeat(100);
        final String title = "\uD83D\uDE00".repeat(150);
        final Filing filing = filings.save(Filing.submit(UUID.randomUUID(), company, title, "text", T0));
        flushAndClear();
        filings.changeStatus(filing.id(), 0L, FilingStatus.FAILED, "\uD83D\uDE00".repeat(500), T0);

        final Filing loaded = filings.findById(filing.id()).orElseThrow();
        assertThat(loaded.companyName()).isEqualTo(company).hasSize(200);
        assertThat(loaded.title()).isEqualTo(title).hasSize(300);
        assertThat(loaded.failureReason()).hasSize(1000);
    }

    @Test
    void returnsUnpublishedEventsOldestFirst() {
        final OutboxEvent newer = saveEvent(T0.plusSeconds(2));
        final OutboxEvent older = saveEvent(T0);
        final OutboxEvent published = saveEvent(T0.plusSeconds(1));
        outbox.markPublished(published.id(), T0.plusSeconds(3));

        final List<OutboxEvent> batch = outbox.findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(10));

        assertThat(batch).extracting(OutboxEvent::id).containsExactly(older.id(), newer.id());
        assertThat(outbox.findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(1)))
                .extracting(OutboxEvent::id).containsExactly(older.id());
    }

    @Test
    void leavesParkedEventsOutOfTheBatchAndKeepsTheOrderOfTheOthers() {
        final OutboxEvent first = saveEvent(T0);
        final OutboxEvent parked = saveEvent(T0.plusSeconds(1));
        final OutboxEvent last = saveEvent(T0.plusSeconds(2));
        outbox.recordFailedAttempt(parked.id(), "boom");
        outbox.parkIfExhausted(parked.id(), 1, T0.plusSeconds(3));

        assertThat(outbox.findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(10)))
                .extracting(OutboxEvent::id).containsExactly(first.id(), last.id());
    }

    @Test
    void countsFailedAttemptsAndParksOnlyAtTheMaximum() {
        final OutboxEvent event = saveEvent(T0);

        outbox.recordFailedAttempt(event.id(), "first");
        final int parkedTooEarly = outbox.parkIfExhausted(event.id(), 2, T0.plusSeconds(1));
        outbox.recordFailedAttempt(event.id(), "second");
        final int parked = outbox.parkIfExhausted(event.id(), 2, T0.plusSeconds(2));

        assertThat(parkedTooEarly).isZero();
        assertThat(parked).isEqualTo(1);
        assertThat(outboxColumns(event.id())).containsEntry("ATTEMPTS", 2).containsEntry("LAST_ERROR", "second");
        assertThat(outbox.parkIfExhausted(event.id(), 2, T0.plusSeconds(3))).as("parked only once").isZero();
        assertThat(outbox.recordFailedAttempt(event.id(), "after parking")).as("a parked row is not counted").isZero();
    }

    @Test
    void doesNotCountOrParkAPublishedEvent() {
        final OutboxEvent event = saveEvent(T0);
        outbox.markPublished(event.id(), T0.plusSeconds(1));

        assertThat(outbox.recordFailedAttempt(event.id(), "late")).isZero();
        assertThat(outbox.parkIfExhausted(event.id(), 0, T0.plusSeconds(2))).isZero();
    }

    @Test
    void anEventStartsWithNoAttemptsAndUnparked() {
        final OutboxEvent event = saveEvent(T0);

        assertThat(outboxColumns(event.id())).containsEntry("ATTEMPTS", 0).containsEntry("LAST_ERROR", null)
                .containsEntry("PARKED_AT", null);
    }

    @Test
    void marksAnEventPublishedOnlyOnce() {
        final OutboxEvent event = saveEvent(T0);

        final int first = outbox.markPublished(event.id(), T0.plusSeconds(1));
        final int second = outbox.markPublished(event.id(), T0.plusSeconds(2));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(outbox.findById(event.id()).orElseThrow().publishedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(outbox.markPublished(UUID.randomUUID(), T0)).isZero();
    }

    private Filing save(final Instant submittedAt) {
        final Filing filing = filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", submittedAt));
        filings.flush();
        return filing;
    }

    private OutboxEvent saveEvent(final Instant createdAt) {
        final OutboxEvent event = outbox.save(new OutboxEvent(UUID.randomUUID(), "filing.submitted", "corr", "{}", createdAt));
        outbox.flush();
        return event;
    }

    private Map<String, Object> outboxColumns(final UUID id) {
        return jdbc.queryForMap("select attempts, last_error, parked_at from outbox where id = ?", id);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
