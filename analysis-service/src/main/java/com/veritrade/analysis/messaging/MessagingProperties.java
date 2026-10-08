package com.veritrade.analysis.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the event publisher.
 *
 * @param confirmTimeout how long to wait for the broker's publisher confirm before the publish counts as failed
 */
@ConfigurationProperties("veritrade.analysis.messaging")
public record MessagingProperties(@DefaultValue("5s") Duration confirmTimeout) {
}
