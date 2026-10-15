package com.haanh.ragassistant.controller;

import com.haanh.ragassistant.dto.RagQueryRequest;
import com.haanh.ragassistant.dto.RagQueryResponse;
import com.haanh.ragassistant.service.RagOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
@Tag(name = "RAG Document Assistant", description = "Query endpoint featuring Redis Semantic Caching and strict citation-grounded RAG")
public class RagQueryController {

    private final RagOrchestratorService ragOrchestratorService;

    public RagQueryController(RagOrchestratorService ragOrchestratorService) {
        this.ragOrchestratorService = ragOrchestratorService;
    }

    @PostMapping("/ask")
    @Operation(
        summary = "Ask Enterprise Document Assistant",
        description = "Processes a natural language question. First checks Redis Semantic Cache for questions with >=0.92 cosine similarity (<50ms response). If cache misses, performs pgvector HNSW search, feeds grounded context to Gemini 2.5 Flash, and returns structured JSON with verified citations.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Query processed successfully", content = @Content(schema = @Schema(implementation = RagQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or blank question"),
            @ApiResponse(responseCode = "502", description = "Gemini API failure")
        }
    )
    public ResponseEntity<RagQueryResponse> ask(@Valid @RequestBody RagQueryRequest request) {
        RagQueryResponse response = ragOrchestratorService.processQuery(request);
        return ResponseEntity.ok(response);
    }
}
