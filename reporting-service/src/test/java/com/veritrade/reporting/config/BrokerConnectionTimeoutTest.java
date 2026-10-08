package com.veritrade.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

/**
 * Found by the end-to-end suite: while the broker container is stopped, a connection attempt hung for
 * the default 60 s, so a shutdown during the outage outlasted Docker's grace period, the service was
 * killed and H2 lost recently committed rows.
 */
class BrokerConnectionTimeoutTest {

    /** Docker kills a container that has not exited 10 s after SIGTERM. */
    private static final Duration DOCKER_STOP_GRACE_PERIOD = Duration.ofSeconds(10);
    /** A shutdown can wait for a few connection attempts one after the other (listener, health check, publisher). */
    private static final int CONNECTION_ATTEMPTS_DURING_SHUTDOWN = 3;

    @Test
    void connectionAttemptsCannotOutlastTheDockerStopGracePeriod() {
        final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        final String timeout = yaml.getObject().getProperty("spring.rabbitmq.connection-timeout");

        assertThat(timeout).isNotNull();
        assertThat(DurationStyle.detectAndParse(timeout).multipliedBy(CONNECTION_ATTEMPTS_DURING_SHUTDOWN))
                .isLessThan(DOCKER_STOP_GRACE_PERIOD);
    }
}
