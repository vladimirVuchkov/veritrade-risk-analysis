package com.veritrade.ingestion.config;

import com.veritrade.ingestion.messaging.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the outbox publisher with a fixed delay, so two runs never overlap. Only one instance of the
 * service may run the publisher; {@code ingestion.outbox.enabled=false} turns it off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ingestion.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfig implements SchedulingConfigurer {

    private final OutboxPublisher publisher;
    private final IngestionProperties properties;

    public OutboxSchedulingConfig(final OutboxPublisher publisher, final IngestionProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public void configureTasks(final ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(publisher::publishPending, properties.outbox().publishInterval());
    }
}
