package com.veritrade.reporting.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 response for a report that does not exist (yet). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String NOT_FOUND_TITLE = "Report not found";

    @ExceptionHandler(ReportNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail reportNotFound(final ReportNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle(NOT_FOUND_TITLE);
        return problem;
    }
}
