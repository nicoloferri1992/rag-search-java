package com.ragsearch.application;

import com.ragsearch.config.RagProperties;
import com.ragsearch.domain.model.DocumentChunk;
import com.ragsearch.domain.port.ChunkRepository;
import com.ragsearch.domain.port.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Orchestrates ingestion of a raw document: chunking, embedding, persistence. */
@Service
public class IngestionService {

    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final RagProperties properties;

    IngestionService(
            ChunkingService chunkingService,
            EmbeddingClient embeddingClient,
            ChunkRepository chunkRepository,
            RagProperties properties) {
        this.chunkingService = chunkingService;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.properties = properties;
    }

    public int ingest(String documentId, String text) {
        List<String> chunkTexts = chunkingService.chunk(
                text, properties.chunking().sizeWords(), properties.chunking().overlapWords());
        if (chunkTexts.isEmpty()) {
            return 0;
        }

        List<float[]> embeddings = embeddingClient.embedAll(chunkTexts);

        List<DocumentChunk> chunks = new ArrayList<>(chunkTexts.size());
        for (int i = 0; i < chunkTexts.size(); i++) {
            chunks.add(new DocumentChunk(UUID.randomUUID(), documentId, i, chunkTexts.get(i), embeddings.get(i)));
        }

        chunkRepository.saveAll(chunks);
        return chunks.size();
    }
}
