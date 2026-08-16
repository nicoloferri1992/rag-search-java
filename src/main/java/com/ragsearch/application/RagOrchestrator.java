package com.ragsearch.application;

import com.ragsearch.config.RagProperties;
import com.ragsearch.domain.model.RagAnswer;
import com.ragsearch.domain.model.RetrievedChunk;
import com.ragsearch.domain.port.ChunkRepository;
import com.ragsearch.domain.port.CompletionClient;
import com.ragsearch.domain.port.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/** Coordinates hybrid retrieval and grounded answer generation for a user question. */
@Service
public class RagOrchestrator {

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's question using ONLY the
            context provided below. If the answer is not contained in the context,
            say you don't know instead of guessing.""";

    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final CompletionClient completionClient;
    private final RagProperties properties;

    RagOrchestrator(
            EmbeddingClient embeddingClient,
            ChunkRepository chunkRepository,
            CompletionClient completionClient,
            RagProperties properties) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.completionClient = completionClient;
        this.properties = properties;
    }

    public RagAnswer answer(String question) {
        float[] queryEmbedding = embeddingClient.embed(question);
        List<RetrievedChunk> topChunks =
                chunkRepository.hybridSearch(question, queryEmbedding, properties.retrieval().topK());

        String context = topChunks.stream()
                .map(RetrievedChunk::content)
                .collect(Collectors.joining("\n\n---\n\n"));

        String userPrompt = "Context:\n" + context + "\n\nQuestion: " + question;
        String answer = completionClient.complete(SYSTEM_PROMPT, userPrompt);

        return new RagAnswer(answer, topChunks);
    }
}
