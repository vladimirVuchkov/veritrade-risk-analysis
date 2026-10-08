package com.veritrade.reporting.api;

import com.veritrade.reporting.api.dto.ReportResponse;
import com.veritrade.reporting.service.ReportService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 404 until Reporting has consumed the terminal analysis event of the filing. The OpenAPI defines
     * no 400 here, so an id that is not a UUID is answered like any other unknown filing.
     */
    @GetMapping("/{filingId}")
    public ReportResponse getReport(@PathVariable String filingId) {
        UUID id = parse(filingId);
        return reportService.findReport(id)
                .map(ReportResponse::from)
                .orElseThrow(() -> ReportNotFoundException.notReady(id));
    }

    private static UUID parse(String filingId) {
        try {
            return UUID.fromString(filingId);
        } catch (IllegalArgumentException e) {
            throw ReportNotFoundException.malformedId();
        }
    }
}
