package com.ragsearch.domain.port;

import java.util.List;

/** Outbound port for computing dense vector embeddings of text. */
public interface EmbeddingClient {

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);
}
