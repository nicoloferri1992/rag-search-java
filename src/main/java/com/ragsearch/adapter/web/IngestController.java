package com.ragsearch.adapter.web;

import com.ragsearch.application.IngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
class IngestController {

    private final IngestionService ingestionService;

    IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        int chunkCount = ingestionService.ingest(request.documentId(), request.text());
        return ResponseEntity.ok(new IngestResponse(request.documentId(), chunkCount));
    }

    record IngestRequest(@NotBlank String documentId, @NotBlank String text) {
    }

    record IngestResponse(String documentId, int chunksIndexed) {
    }
}
