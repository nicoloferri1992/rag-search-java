package com.ragsearch.adapter.embedding;

/** Signals a failure while calling the embedding provider. */
public class EmbeddingClientException extends RuntimeException {

    public EmbeddingClientException(String message) {
        super(message);
    }
}
