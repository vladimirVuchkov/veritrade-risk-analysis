package com.veritrade.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A submitted filing. The status columns are changed only through
 * {@code FilingRepository.changeStatus}, so a status event never loads the content.
 */
@Entity
@Table(name = "filings")
public class Filing {

    @Id
    private UUID id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FilingStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    protected Filing() {
    }

    private Filing(UUID id, String companyName, String title, String content, Instant submittedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.companyName = Objects.requireNonNull(companyName, "companyName");
        this.title = Objects.requireNonNull(title, "title");
        this.content = Objects.requireNonNull(content, "content");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.updatedAt = submittedAt;
        this.status = FilingStatus.SUBMITTED;
    }

    public static Filing submit(UUID id, String companyName, String title, String content, Instant submittedAt) {
        return new Filing(id, companyName, title, content, submittedAt);
    }

    public UUID id() {
        return id;
    }

    public String companyName() {
        return companyName;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public FilingStatus status() {
        return status;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String failureReason() {
        return failureReason;
    }
}
