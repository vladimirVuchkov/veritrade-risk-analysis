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

    /**
     * Moves the filing to {@code target} when the lifecycle allows it. The failure reason is kept
     * only for {@link FilingStatus#FAILED}. A duplicate or rejected change leaves the filing untouched.
     */
    public StatusChange changeStatus(FilingStatus target, String reason, Instant at) {
        if (status == target) {
            return StatusChange.DUPLICATE;
        }
        if (!status.canMoveTo(target)) {
            return StatusChange.REJECTED;
        }
        status = target;
        failureReason = target == FilingStatus.FAILED ? reason : null;
        updatedAt = at;
        return StatusChange.APPLIED;
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
