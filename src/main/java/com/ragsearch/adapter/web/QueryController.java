package com.ragsearch.adapter.web;

import com.ragsearch.application.RagOrchestrator;
import com.ragsearch.domain.model.RagAnswer;
import com.ragsearch.domain.model.RetrievedChunk;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/query")
class QueryController {

    private final RagOrchestrator ragOrchestrator;

    QueryController(RagOrchestrator ragOrchestrator) {
        this.ragOrchestrator = ragOrchestrator;
    }

    @PostMapping
    ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        RagAnswer ragAnswer = ragOrchestrator.answer(request.question());
        return ResponseEntity.ok(QueryResponse.from(ragAnswer));
    }

    record QueryRequest(@NotBlank String question) {
    }

    record QueryResponse(String answer, List<Source> sources) {
        static QueryResponse from(RagAnswer ragAnswer) {
            List<Source> sources = ragAnswer.sources().stream()
                    .map(Source::from)
                    .toList();
            return new QueryResponse(ragAnswer.answer(), sources);
        }
    }

    record Source(UUID chunkId, String documentId, String content, double score) {
        static Source from(RetrievedChunk chunk) {
            return new Source(chunk.id(), chunk.documentId(), chunk.content(), chunk.score());
        }
    }
}
