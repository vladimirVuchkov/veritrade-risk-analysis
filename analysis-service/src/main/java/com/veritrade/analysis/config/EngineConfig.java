package com.veritrade.analysis.config;

import com.veritrade.analysis.engine.ExcerptExtractor;
import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.engine.RiskScorer;
import com.veritrade.analysis.engine.RuleLoader;
import com.veritrade.analysis.engine.RuleMatcher;
import com.veritrade.analysis.engine.RuleSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/** Wires the Spring-free rule engine. An invalid rules file fails this configuration, so the service does not start. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RulesProperties.class)
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    RuleSet ruleSet(RulesProperties properties) {
        Resource location = properties.location();
        try (InputStream in = location.getInputStream()) {
            RuleSet ruleSet = new RuleLoader().load(in, location.getDescription());
            log.info("Loaded {} risk rules, version {}, from {}",
                    ruleSet.rules().size(), ruleSet.rulesVersion(), location.getDescription());
            return ruleSet;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the risk rules from " + location.getDescription(), e);
        }
    }

    @Bean
    RiskAnalyzer riskAnalyzer(RuleSet ruleSet, RulesProperties properties) {
        return new RiskAnalyzer(
                ruleSet,
                new RuleMatcher(properties.maxMatchesPerRule(), properties.maxMatchedTextChars()),
                new ExcerptExtractor(properties.excerptContextChars(), properties.maxExcerptChars()),
                new RiskScorer(properties.escalationThreshold()));
    }
}
