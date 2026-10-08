package com.veritrade.analysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.engine.RuleSet;
import com.veritrade.analysis.engine.RuleValidationException;
import com.veritrade.contracts.model.RiskLevel;
import java.io.FileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EngineConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EngineConfig.class);

    @Test
    void loadsTheBundledRulesByDefault() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean(RuleSet.class).rules()).hasSizeGreaterThanOrEqualTo(20);
            assertThat(app.getBean(RiskAnalyzer.class).analyze("pending litigation").overallRiskLevel())
                    .isEqualTo(RiskLevel.HIGH);
        });
    }

    @Test
    void refusesToStartWithAnInvalidRulesFile() {
        context.withPropertyValues("veritrade.analysis.rules.location=classpath:rules/invalid-rules.yml")
                .run(app -> assertThat(app).getFailure()
                        .hasRootCauseInstanceOf(RuleValidationException.class)
                        .rootCause()
                        .hasMessageContaining("invalid-rules.yml")
                        .hasMessageContaining("invalid regex 'pending (litigation'")
                        .hasMessageContaining("duplicate rule id LEGAL-001"));
    }

    @Test
    void refusesToStartWhenTheRulesFileIsMissing() {
        context.withPropertyValues("veritrade.analysis.rules.location=classpath:rules/does-not-exist.yml")
                .run(app -> assertThat(app).getFailure()
                        .hasMessageContaining("Cannot read the risk rules from")
                        .hasRootCauseInstanceOf(FileNotFoundException.class));
    }

    @Test
    void refusesToStartWithAnInvalidLimit() {
        context.withPropertyValues("veritrade.analysis.rules.max-matches-per-rule=0")
                .run(app -> assertThat(app).getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause().hasMessageContaining("maxMatchesPerRule must be >= 1"));
    }

    @Test
    void appliesTheConfiguredLimits() {
        context.withPropertyValues(
                        "veritrade.analysis.rules.location=classpath:rules/single-rule.yml",
                        "veritrade.analysis.rules.max-matches-per-rule=2",
                        "veritrade.analysis.rules.excerpt-context-chars=1",
                        "veritrade.analysis.rules.escalation-threshold=2")
                .run(app -> {
                    AnalysisResult result = app.getBean(RiskAnalyzer.class).analyze("a risk, a risk, a risk");

                    assertThat(result.rulesVersion()).isEqualTo("test-2");
                    assertThat(result.totalFindings()).isEqualTo(2);
                    assertThat(result.findings().getFirst().excerpt()).isEqualTo(" risk,");
                    assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
                });
    }
}
