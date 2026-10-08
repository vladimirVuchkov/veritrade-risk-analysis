package com.veritrade.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.messaging.FailedAnalysisRecoverer;
import com.veritrade.analysis.messaging.FilingSubmittedListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The context starts without a broker; the listener container is not started here (see the *IT tests). */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class AnalysisApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithTheEngineAndTheListener() {
        assertThat(context.getBean(RiskAnalyzer.class)).isNotNull();
        assertThat(context.getBean(FilingSubmittedListener.class)).isNotNull();
        assertThat(context.getBean(FailedAnalysisRecoverer.class)).isNotNull();
    }
}
