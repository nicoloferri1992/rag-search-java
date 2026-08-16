package com.ragsearch.adapter.embedding;

import com.ragsearch.config.RagProperties;
import com.ragsearch.domain.port.EmbeddingClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Embedding adapter calling the OpenAI-compatible /v1/embeddings endpoint. */
@Component
class OpenAiEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final RagProperties properties;

    OpenAiEmbeddingClient(RestClient.Builder restClientBuilder, RagProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.openai().baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.openai().apiKey())
                .build();
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        EmbeddingRequest request = new EmbeddingRequest(properties.openai().embeddingModel(), texts);

        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null) {
            throw new EmbeddingClientException("empty response from embeddings endpoint");
        }

        return response.data().stream().map(EmbeddingResponse.Item::embedding).toList();
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    private record EmbeddingResponse(List<Item> data) {
        private record Item(float[] embedding) {
        }
    }
}
