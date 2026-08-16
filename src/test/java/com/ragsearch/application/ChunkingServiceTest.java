package com.ragsearch.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkingServiceTest {

    private final ChunkingService sut = new ChunkingService();

    @Test
    void should_return_single_chunk_when_text_shorter_than_chunk_size() {
        // given
        String text = "one two three";

        // when
        List<String> chunks = sut.chunk(text, 10, 2);

        // then
        assertThat(chunks).containsExactly("one two three");
    }

    @Test
    void should_split_into_overlapping_chunks_when_text_longer_than_chunk_size() {
        // given
        String text = "a b c d e f g h";

        // when
        List<String> chunks = sut.chunk(text, 4, 1);

        // then
        assertThat(chunks).containsExactly("a b c d", "d e f g", "g h");
    }

    @Test
    void should_return_empty_list_when_text_is_blank() {
        // when
        List<String> chunks = sut.chunk("   ", 10, 2);

        // then
        assertThat(chunks).isEmpty();
    }

    @Test
    void should_throw_when_overlap_greater_than_or_equal_to_chunk_size() {
        // when / then
        assertThatThrownBy(() -> sut.chunk("a b c", 5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapWords");
    }
}
