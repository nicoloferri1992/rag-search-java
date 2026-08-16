package com.ragsearch.domain.model;

import java.util.UUID;

/** A chunk returned by hybrid retrieval, carrying the fused relevance score. */
public record RetrievedChunk(UUID id, String documentId, String content, double score) {
}
