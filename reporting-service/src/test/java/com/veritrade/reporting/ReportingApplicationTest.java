package com.veritrade.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reporting;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class ReportingApplicationTest {

    @Test
    void contextLoads() {
    }
}
