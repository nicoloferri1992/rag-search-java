package com.ragsearch.domain.port;

import com.ragsearch.domain.model.DocumentChunk;
import com.ragsearch.domain.model.RetrievedChunk;

import java.util.List;

/** Persistence port for document chunks, exposing dense, sparse and hybrid retrieval. */
public interface ChunkRepository {

    void saveAll(List<DocumentChunk> chunks);

    /**
     * Hybrid retrieval combining dense (vector) and sparse (full-text) search via
     * Reciprocal Rank Fusion, executed as a single query in the adapter.
     *
     * @param queryText      raw query text, used for the sparse/full-text side
     * @param queryEmbedding dense embedding of the query, used for the vector side
     * @param topK           number of chunks to return
     */
    List<RetrievedChunk> hybridSearch(String queryText, float[] queryEmbedding, int topK);
}
