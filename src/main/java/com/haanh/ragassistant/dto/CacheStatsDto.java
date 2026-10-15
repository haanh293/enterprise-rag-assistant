package com.haanh.ragassistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Semantic cache performance statistics and latency metrics")
public record CacheStatsDto(
    @Schema(description = "Total number of queries handled", example = "1250")
    long totalQueries,

    @Schema(description = "Total semantic cache hits", example = "890")
    long cacheHits,

    @Schema(description = "Total semantic cache misses", example = "360")
    long cacheMisses,

    @Schema(description = "Cache hit ratio (0.0 to 1.0)", example = "0.712")
    double hitRatio,

    @Schema(description = "Average response time for cache hits in milliseconds", example = "28.5")
    double avgCacheLatencyMs,

    @Schema(description = "Average response time for cache misses (Hybrid Search + Gemini) in milliseconds", example = "1240.0")
    double avgDbLatencyMs,

    @Schema(description = "Total estimated tokens saved across all cache hits", example = "756500")
    long totalTokensSaved
) {}
