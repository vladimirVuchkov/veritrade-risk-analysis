package com.veritrade.ingestion.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.service.FilingNotFoundException;
import com.veritrade.ingestion.service.FilingService;
import com.veritrade.ingestion.service.FilingSubmission;
import com.veritrade.ingestion.service.InvalidRequestException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(FilingController.class)
@EnableConfigurationProperties(IngestionProperties.class)
class FilingControllerTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-07T12:00:00Z");
    private static final String VALID_BODY = """
            {"companyName":"Acme Holdings Inc.","title":"Form 10-K","content":"Risk factors."}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FilingService filingService;

    @Test
    void acceptsAFilingWith202LocationAndBody() throws Exception {
        UUID id = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12");
        when(filingService.submit(any(), anyString())).thenReturn(view(id, FilingStatus.SUBMITTED, null));

        submit(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/filings/" + id))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.filingId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        verify(filingService).submit(eq(new FilingSubmission("Acme Holdings Inc.", "Form 10-K", "Risk factors.")),
                anyString());
    }

    @Test
    void passesTheRequestCorrelationIdAndEchoesIt() throws Exception {
        when(filingService.submit(any(), anyString())).thenReturn(view(UUID.randomUUID(), FilingStatus.SUBMITTED, null));

        mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("X-Correlation-Id", "corr-42"))
                .andExpect(header().string("X-Correlation-Id", "corr-42"));
        verify(filingService).submit(any(), eq("corr-42"));
    }

    @Test
    void generatesACorrelationIdWhenAbsent() throws Exception {
        when(filingService.submit(any(), anyString())).thenReturn(view(UUID.randomUUID(), FilingStatus.SUBMITTED, null));

        submit(VALID_BODY).andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void invalidFilingIsAProblemDetailWithEveryError() throws Exception {
        when(filingService.submit(any(), anyString())).thenThrow(
                new InvalidRequestException(List.of("title must not be blank", "content must not be blank")));

        expectProblem(submit(VALID_BODY), 400, "Invalid request")
                .andExpect(jsonPath("$.detail").value("title must not be blank; content must not be blank"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0]").value("title must not be blank"))
                .andExpect(jsonPath("$.instance").value("/api/filings"));
    }

    @Test
    void malformedJsonIsAProblemDetail() throws Exception {
        expectProblem(submit("{\"companyName\":"), 400, "Bad Request");
        verifyNoInteractions(filingService);
    }

    @Test
    void missingBodyIsAProblemDetail() throws Exception {
        expectProblem(mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)), 400, "Bad Request");
    }

    @Test
    void jsonOfTheWrongShapeIsAProblemDetail() throws Exception {
        expectProblem(submit("[1,2,3]"), 400, "Bad Request");
        expectProblem(submit("{\"companyName\":{\"nested\":true}}"), 400, "Bad Request");
    }

    @Test
    void unsupportedContentTypeIsAProblemDetail() throws Exception {
        expectProblem(mvc.perform(post("/api/filings").contentType(MediaType.TEXT_PLAIN).content("text")),
                415, "Unsupported Media Type");
    }

    @Test
    void returnsAFilingStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(filingService.get(id)).thenReturn(view(id, FilingStatus.FAILED, "rule engine error"));

        mvc.perform(get("/api/filings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filingId").value(id.toString()))
                .andExpect(jsonPath("$.companyName").value("Acme"))
                .andExpect(jsonPath("$.title").value("10-K"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-07T12:00:00Z"))
                .andExpect(jsonPath("$.failureReason").value("rule engine error"))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void unknownFilingIsA404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(filingService.get(id)).thenThrow(new FilingNotFoundException(id));

        expectProblem(mvc.perform(get("/api/filings/{id}", id)), 404, "Filing not found")
                .andExpect(jsonPath("$.detail").value("No filing with id " + id))
                .andExpect(jsonPath("$.instance").value("/api/filings/" + id));
    }

    @Test
    void malformedFilingIdIsA400ProblemDetail() throws Exception {
        expectProblem(mvc.perform(get("/api/filings/{id}", "not-a-uuid")), 400, "Bad Request");
        verifyNoInteractions(filingService);
    }

    @Test
    void listsFilingsInTheOrderOfTheService() throws Exception {
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        when(filingService.listRecent(null)).thenReturn(List.of(
                view(newer, FilingStatus.SUBMITTED, null), view(older, FilingStatus.COMPLETED, null)));

        mvc.perform(get("/api/filings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].filingId").value(newer.toString()))
                .andExpect(jsonPath("$[1].filingId").value(older.toString()))
                .andExpect(jsonPath("$[1].failureReason").isEmpty());
    }

    @Test
    void passesTheLimitToTheService() throws Exception {
        when(filingService.listRecent(5)).thenReturn(List.of());

        mvc.perform(get("/api/filings").param("limit", "5")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void limitRejectedByTheServiceIsAProblemDetail() throws Exception {
        when(filingService.listRecent(0)).thenThrow(new InvalidRequestException(List.of("limit must be between 1 and 100")));

        expectProblem(mvc.perform(get("/api/filings").param("limit", "0")), 400, "Invalid request")
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 100"));
    }

    @Test
    void nonNumericLimitIsAProblemDetail() throws Exception {
        expectProblem(mvc.perform(get("/api/filings").param("limit", "abc")), 400, "Bad Request");
        expectProblem(mvc.perform(get("/api/filings").param("limit", "1.5")), 400, "Bad Request");
        expectProblem(mvc.perform(get("/api/filings").param("limit", "99999999999")), 400, "Bad Request");
        verifyNoInteractions(filingService);
    }

    @Test
    void unexpectedErrorIsA500ProblemDetailWithoutInternals() throws Exception {
        when(filingService.listRecent(null)).thenThrow(new IllegalStateException("secret internals"));

        expectProblem(mvc.perform(get("/api/filings")), 500, "Internal error")
                .andExpect(jsonPath("$.detail").value("The request could not be processed"));
    }

    @Test
    void unsupportedMethodIsAProblemDetail() throws Exception {
        expectProblem(mvc.perform(delete("/api/filings/{id}", UUID.randomUUID())), 405, "Method Not Allowed");
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static ResultActions expectProblem(ResultActions actions, int status, String title) throws Exception {
        return actions
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    private static FilingView view(UUID id, FilingStatus status, String failureReason) {
        return new FilingView(id, "Acme", "10-K", status, SUBMITTED_AT, failureReason);
    }
}
