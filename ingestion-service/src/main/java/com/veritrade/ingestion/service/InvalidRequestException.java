package com.veritrade.ingestion.service;

import java.util.List;

/** Input that breaks one or more rules of the API; each error is a short sentence for the client. */
public class InvalidRequestException extends RuntimeException {

    private final List<String> errors;

    public InvalidRequestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
