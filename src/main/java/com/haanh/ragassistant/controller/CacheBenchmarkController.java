package com.haanh.ragassistant.controller;

import com.haanh.ragassistant.dto.CacheStatsDto;
import com.haanh.ragassistant.service.SemanticCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache")
@Tag(name = "Semantic Cache Benchmark", description = "Endpoints for monitoring Redis Semantic Cache performance and latency metrics")
public class CacheBenchmarkController {

    private final SemanticCacheService semanticCacheService;

    public CacheBenchmarkController(SemanticCacheService semanticCacheService) {
        this.semanticCacheService = semanticCacheService;
    }

    @GetMapping("/stats")
    @Operation(
        summary = "Get Semantic Cache Performance Statistics",
        description = "Returns real-time performance indicators including total queries, cache hit ratio, average latency on cache hit (<50ms) vs cache miss (~1000ms+), and estimated total LLM tokens saved.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Cache statistics retrieved successfully", content = @Content(schema = @Schema(implementation = CacheStatsDto.class)))
        }
    )
    public ResponseEntity<CacheStatsDto> getStats() {
        return ResponseEntity.ok(semanticCacheService.getStats());
    }

    @DeleteMapping("/clear")
    @Operation(
        summary = "Flush Semantic Cache",
        description = "Flushes all semantic cache keys stored in Redis and resets performance counters.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Semantic cache cleared successfully")
        }
    )
    public ResponseEntity<Map<String, String>> clearCache() {
        semanticCacheService.clearCache();
        return ResponseEntity.ok(Map.of("message", "Semantic cache successfully purged and metrics reset"));
    }
}
