package com.ragsearch.adapter.llm;

/** Signals a failure while calling the completion (chat) provider. */
public class CompletionClientException extends RuntimeException {

    public CompletionClientException(String message) {
        super(message);
    }
}
