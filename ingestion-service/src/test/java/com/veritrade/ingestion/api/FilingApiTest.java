package com.veritrade.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.repository.FilingRepository;
import com.veritrade.ingestion.repository.OutboxRepository;
import com.veritrade.ingestion.support.Contracts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** The REST API end to end through the real services and database (no broker: the publisher is off). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:filing-api;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ingestion.outbox.enabled=false"
})
@AutoConfigureMockMvc
class FilingApiTest {

    private static final int TWO_MB = 2_097_152;
    private static final String EURO = "€";
    private static final String EMOJI = "😀";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private FilingRepository filings;

    @MockitoSpyBean
    private OutboxRepository outbox;

    @BeforeEach
    void emptyTables() {
        outbox.deleteAll();
        filings.deleteAll();
    }

    @Test
    void storesTheFilingAndItsEventTogether() throws Exception {
        UUID id = submitted(submit("Acme", "10-K", "Risk factors.").andExpect(status().isAccepted()));

        OutboxEvent row = outbox.findById(EventIds.forFiling(id, EventType.FILING_SUBMITTED)).orElseThrow();
        assertThat(row.routingKey()).isEqualTo("filing.submitted");
        assertThat(row.publishedAt()).isNull();
        assertThat(Contracts.validate(EventType.FILING_SUBMITTED, row.payload())).isEmpty();
        mvc.perform(get("/api/filings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.failureReason").isEmpty());
    }

    @Test
    void storesTheRequestCorrelationIdWithTheEvent() throws Exception {
        MvcResult result = mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "trace-77").content(body("Acme", "10-K", "text")))
                .andExpect(status().isAccepted()).andReturn();

        UUID id = UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).get("filingId").asString());
        OutboxEvent row = outbox.findById(EventIds.forFiling(id, EventType.FILING_SUBMITTED)).orElseThrow();
        assertThat(row.correlationId()).isEqualTo("trace-77");
        assertThat(jsonMapper.readTree(row.payload()).get("correlationId").asString()).isEqualTo("trace-77");
    }

    @Test
    void acceptsContentOfExactlyTwoMegabytes() throws Exception {
        UUID id = submitted(submit("Acme", "10-K", "a".repeat(TWO_MB)).andExpect(status().isAccepted()));

        assertThat(filings.findById(id).orElseThrow().content()).hasSize(TWO_MB);
        OutboxEvent row = outbox.findById(EventIds.forFiling(id, EventType.FILING_SUBMITTED)).orElseThrow();
        assertThat(Contracts.validate(EventType.FILING_SUBMITTED, row.payload())).isEmpty();
    }

    @Test
    void rejectsContentOfTwoMegabytesAndOneByteAndStoresNothing() throws Exception {
        expectBadRequest(submit("Acme", "10-K", "a".repeat(TWO_MB + 1)))
                .andExpect(jsonPath("$.errors[0]").value("content must be at most 2097152 bytes of UTF-8 (was 2097153)"));

        assertThat(filings.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void measuresMultibyteContentInBytes() throws Exception {
        String exactly = "a".repeat(TWO_MB - 3) + EURO;
        String overByOne = "a".repeat(TWO_MB - 2) + EURO;

        submit("Acme", "10-K", exactly).andExpect(status().isAccepted());
        expectBadRequest(submit("Acme", "10-K", overByOne));
    }

    @Test
    void storesCompanyNameAndTitleOfExactlyTheColumnLength() throws Exception {
        String company = EMOJI.repeat(100);
        String title = EURO.repeat(300);

        UUID id = submitted(submit(company, title, "text").andExpect(status().isAccepted()));

        mvc.perform(get("/api/filings/{id}", id))
                .andExpect(jsonPath("$.companyName").value(company))
                .andExpect(jsonPath("$.title").value(title));
    }

    @Test
    void rejectsCompanyNameAndTitleOverTheLimit() throws Exception {
        expectBadRequest(submit("c".repeat(201), "t".repeat(301), "text"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0]").value("companyName must be at most 200 characters"))
                .andExpect(jsonPath("$.errors[1]").value("title must be at most 300 characters"));
    }

    @Test
    void rejectsFourByteCharactersBeyondTheColumnLengthInsteadOfFailing() throws Exception {
        expectBadRequest(submit(EMOJI.repeat(101), "10-K", "text"))
                .andExpect(jsonPath("$.errors[0]").value("companyName must be at most 200 characters"));
    }

    @Test
    void rejectsMissingFields() throws Exception {
        expectBadRequest(mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(jsonPath("$.errors", hasSize(3)));
    }

    @Test
    void rejectsNullAndWhitespaceFields() throws Exception {
        expectBadRequest(mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":null,\"title\":\"  \",\"content\":\"\\n\\t\"}")))
                .andExpect(jsonPath("$.detail").value(
                        "companyName must not be blank; title must not be blank; content must not be blank"));
    }

    @Test
    void ignoresUnknownRequestFields() throws Exception {
        mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"Acme\",\"title\":\"10-K\",\"content\":\"x\",\"extra\":1}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void doesNotStoreTheFilingWhenTheEventCannotBeStored() throws Exception {
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).save(any());

        submit("Acme", "10-K", "text").andExpect(status().isInternalServerError());

        assertThat(filings.count()).isZero();
    }

    @Test
    void listsNewestFirstWithTheDefaultLimit() throws Exception {
        List<UUID> ids = submitMany(22);

        MvcResult result = mvc.perform(get("/api/filings")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(20))).andReturn();

        assertThat(listedIds(result)).containsExactlyElementsOf(ids.reversed().subList(0, 20));
    }

    @Test
    void listsExactlyTheRequestedNumber() throws Exception {
        List<UUID> ids = submitMany(3);

        MvcResult result = mvc.perform(get("/api/filings").param("limit", "1"))
                .andExpect(jsonPath("$", hasSize(1))).andReturn();

        assertThat(listedIds(result)).containsExactly(ids.getLast());
        mvc.perform(get("/api/filings").param("limit", "100")).andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void emptyLimitMeansTheDefault() throws Exception {
        submitMany(2);

        mvc.perform(get("/api/filings").param("limit", "")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void listsNothingWhenThereAreNoFilings() throws Exception {
        mvc.perform(get("/api/filings")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "2147483647"})
    void rejectsLimitsOutOfRange(String limit) throws Exception {
        expectBadRequest(mvc.perform(get("/api/filings").param("limit", limit)))
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 100"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.0", "10a", "2147483648"})
    void rejectsLimitsThatAreNotIntegers(String limit) throws Exception {
        expectBadRequest(mvc.perform(get("/api/filings").param("limit", limit)));
    }

    @Test
    void unknownFilingIs404() throws Exception {
        mvc.perform(get("/api/filings/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "3f2b8c1e-6a4d-4e2f-9b7a"})
    void malformedFilingIdIs400(String id) throws Exception {
        expectBadRequest(mvc.perform(get("/api/filings/{id}", id)))
                .andExpect(jsonPath("$.detail", containsString("filingId")));
    }

    private List<UUID> submitMany(int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(submitted(submit("Company " + i, "Title " + i, "text")));
        }
        return ids;
    }

    private List<UUID> listedIds(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString()).valueStream()
                .map(node -> UUID.fromString(node.get("filingId").asString()))
                .toList();
    }

    private UUID submitted(ResultActions actions) throws Exception {
        String json = actions.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(json).get("filingId").asString());
    }

    private ResultActions submit(String companyName, String title, String content) throws Exception {
        return mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                .content(body(companyName, title, content)));
    }

    private String body(String companyName, String title, String content) {
        Map<String, String> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("title", title);
        body.put("content", content);
        return jsonMapper.writeValueAsString(body);
    }

    private static ResultActions expectBadRequest(ResultActions actions) throws Exception {
        return actions.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
