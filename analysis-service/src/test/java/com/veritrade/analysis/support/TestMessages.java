package com.veritrade.analysis.support;

import com.veritrade.analysis.engine.ExcerptExtractor;
import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.engine.RiskScorer;
import com.veritrade.analysis.engine.RuleLoader;
import com.veritrade.analysis.engine.RuleMatcher;
import com.veritrade.analysis.engine.RuleSet;
import com.veritrade.analysis.messaging.MessagingProperties;
import com.veritrade.contracts.event.EventType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.node.ObjectNode;

/** Builds AMQP messages and a real analyzer for messaging tests. */
public final class TestMessages {

    private static final int CAP = 50;
    private static final int MAX_MATCH = 500;
    private static final int MAX_EXCERPT = 1000;
    private static final int CONTEXT = 120;
    private static final int THRESHOLD = 10;
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    /** The default messaging settings, as bound from an empty configuration. */
    public static final MessagingProperties MESSAGING =
            new MessagingProperties(CONFIRM_TIMEOUT, MessagingProperties.SCHEMA_MAX_REASON_LENGTH);

    private TestMessages() {
    }

    /** The contract example of filing.submitted with a fresh filing id. */
    public static ObjectNode filingSubmitted(final UUID filingId) {
        final ObjectNode event = ContractFixtures.example(EventType.FILING_SUBMITTED);
        ((ObjectNode) event.get("payload")).put("filingId", filingId.toString());
        return event;
    }

    public static Message message(final ObjectNode event) {
        return message(event.toString(), false);
    }

    public static Message message(final String body, final boolean redelivered) {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setRedelivered(redelivered);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    public static RiskAnalyzer bundledAnalyzer() {
        final String rules = ContractFixtures.text("risk-rules.yml");
        final RuleSet ruleSet = new RuleLoader().load(
                new ByteArrayInputStream(rules.getBytes(StandardCharsets.UTF_8)), "risk-rules.yml");
        return new RiskAnalyzer(ruleSet, new RuleMatcher(CAP, MAX_MATCH), new ExcerptExtractor(CONTEXT, MAX_EXCERPT),
                new RiskScorer(THRESHOLD));
    }
}
