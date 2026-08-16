package com.ragsearch.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into overlapping word-based chunks.
 * Pure logic, no external dependencies.
 */
@Component
class ChunkingService {

    List<String> chunk(String text, int chunkSizeWords, int overlapWords) {
        if (chunkSizeWords <= 0) {
            throw new IllegalArgumentException("chunkSizeWords must be positive");
        }
        if (overlapWords < 0 || overlapWords >= chunkSizeWords) {
            throw new IllegalArgumentException("overlapWords must be in [0, chunkSizeWords)");
        }

        String[] words = text.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int step = chunkSizeWords - overlapWords;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + chunkSizeWords, words.length);
            chunks.add(String.join(" ", List.of(words).subList(start, end)));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
