package com.ragsearch.domain.model;

import java.util.UUID;

/**
 * A chunk of a source document, ready to be embedded and indexed.
 * The embedding is null until {@link com.ragsearch.domain.port.EmbeddingClient} has processed it.
 */
public record DocumentChunk(
        UUID id,
        String documentId,
        int chunkIndex,
        String content,
        float[] embedding) {

    public DocumentChunk {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    public DocumentChunk withEmbedding(float[] newEmbedding) {
        return new DocumentChunk(id, documentId, chunkIndex, content, newEmbedding);
    }
}
