package com.veritrade.ingestion.api;

import com.veritrade.ingestion.api.dto.FilingStatusResponse;
import com.veritrade.ingestion.api.dto.SubmitFilingRequest;
import com.veritrade.ingestion.api.dto.SubmitFilingResponse;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.service.FilingService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping(FilingController.BASE_PATH)
public class FilingController {

    static final String BASE_PATH = "/api/filings";

    private final FilingService filingService;

    public FilingController(final FilingService filingService) {
        this.filingService = filingService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubmitFilingResponse> submit(
            @RequestBody final SubmitFilingRequest request,
            @RequestAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) final String correlationId) {
        final FilingView filing = filingService.submit(request.toSubmission(), correlationId);
        final URI location = UriComponentsBuilder.fromPath(BASE_PATH).path("/{id}").buildAndExpand(filing.id()).toUri();
        return ResponseEntity.accepted()
                .location(location)
                .body(new SubmitFilingResponse(filing.id(), filing.status()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FilingStatusResponse> list(@RequestParam(required = false) final Integer limit) {
        return filingService.listRecent(limit).stream().map(FilingStatusResponse::from).toList();
    }

    @GetMapping(path = "/{filingId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public FilingStatusResponse get(@PathVariable final UUID filingId) {
        return FilingStatusResponse.from(filingService.get(filingId));
    }
}
