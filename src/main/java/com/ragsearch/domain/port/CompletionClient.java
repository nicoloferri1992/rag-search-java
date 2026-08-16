package com.ragsearch.domain.port;

/** Outbound port for generating a grounded answer from a system+user prompt. */
public interface CompletionClient {

    String complete(String systemPrompt, String userPrompt);
}
