package com.veritrade.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ingestion;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ingestion.outbox.enabled=false"
})
class IngestionApplicationTest {

    @Test
    void contextLoads() {
    }
}
