package com.ragsearch.adapter.llm;

import com.ragsearch.config.RagProperties;
import com.ragsearch.domain.port.CompletionClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Completion adapter calling the OpenAI-compatible /v1/chat/completions endpoint. */
@Component
class OpenAiCompletionClient implements CompletionClient {

    private final RestClient restClient;
    private final RagProperties properties;

    OpenAiCompletionClient(RestClient.Builder restClientBuilder, RagProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.openai().baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.openai().apiKey())
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        ChatRequest request = new ChatRequest(
                properties.openai().chatModel(),
                List.of(new ChatRequest.Message("system", systemPrompt), new ChatRequest.Message("user", userPrompt)));

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new CompletionClientException("empty response from chat completions endpoint");
        }

        return response.choices().get(0).message().content();
    }

    private record ChatRequest(String model, List<Message> messages) {
        private record Message(String role, String content) {
        }
    }

    private record ChatResponse(List<Choice> choices) {
        private record Choice(Message message) {
            private record Message(String content) {
            }
        }
    }
}
