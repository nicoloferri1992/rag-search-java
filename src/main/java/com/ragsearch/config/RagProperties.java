package com.ragsearch.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @NestedConfigurationProperty Chunking chunking,
        @NestedConfigurationProperty Retrieval retrieval,
        @NestedConfigurationProperty OpenAi openai) {

    public record Chunking(@Positive int sizeWords, @Min(0) int overlapWords) {
    }

    public record Retrieval(@Positive int topK, @Positive int rrfK) {
    }

    public record OpenAi(
            @NotBlank String apiKey,
            @NotBlank String embeddingModel,
            @NotBlank String chatModel,
            @NotBlank String baseUrl) {
    }
}
