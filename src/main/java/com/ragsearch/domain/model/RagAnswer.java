package com.ragsearch.domain.model;

import java.util.List;

/** The final answer produced by the LLM, together with the context chunks used to ground it. */
public record RagAnswer(String answer, List<RetrievedChunk> sources) {
}
