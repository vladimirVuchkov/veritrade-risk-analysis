package com.veritrade.reporting.domain;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One rule match stored with its report. */
@Entity
@Table(name = "findings")
public class FindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filing_id", nullable = false)
    private Report report;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private RiskCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "matched_text", nullable = false)
    private String matchedText;

    @Column(name = "excerpt", nullable = false)
    private String excerpt;

    @Column(name = "position", nullable = false)
    private int position;

    protected FindingEntity() {
    }

    public FindingEntity(
            final RiskCategory category, final Severity severity, final String ruleId, final String matchedText, final String excerpt, final int position) {
        this.category = category;
        this.severity = severity;
        this.ruleId = ruleId;
        this.matchedText = matchedText;
        this.excerpt = excerpt;
        this.position = position;
    }

    void attachTo(final Report owner) {
        this.report = owner;
    }

    public Long getId() {
        return id;
    }

    public RiskCategory getCategory() {
        return category;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getMatchedText() {
        return matchedText;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public int getPosition() {
        return position;
    }
}
