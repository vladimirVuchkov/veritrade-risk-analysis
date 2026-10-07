package com.veritrade.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ingestion;DB_CLOSE_DELAY=-1")
class IngestionApplicationTest {

    @Test
    void contextLoads() {
    }
}
