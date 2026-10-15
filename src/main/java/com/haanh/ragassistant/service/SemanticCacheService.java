package com.haanh.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haanh.ragassistant.dto.CacheStatsDto;
import com.haanh.ragassistant.dto.CachedQueryItem;
import com.haanh.ragassistant.dto.RagQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.semantic-cache.similarity-threshold:0.92}")
    private double defaultSimilarityThreshold;

    @Value("${rag.semantic-cache.ttl-seconds:86400}")
    private long cacheTtlSeconds;

    @Value("${rag.semantic-cache.cache-key-prefix:rag:semcache:}")
    private String cacheKeyPrefix;

    // Metrics counters
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong totalTokensSaved = new AtomicLong(0);
    private final AtomicLong totalCacheHitLatencyMs = new AtomicLong(0);
    private final AtomicLong totalCacheMissLatencyMs = new AtomicLong(0);

    public SemanticCacheService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Looks up existing query in Semantic Cache using Cosine Similarity over query embedding.
     * Returns RagQueryResponse if similarity >= threshold, otherwise empty.
     */
    public Optional<RagQueryResponse> lookup(String query, float[] queryEmbedding, Double customThreshold) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();

        double threshold = (customThreshold != null && customThreshold > 0.0) 
            ? customThreshold 
            : defaultSimilarityThreshold;

        try {
            // 1. Exact match fast check via hash key
            String exactKey = buildExactKey(query);
            Object exactVal = redisTemplate.opsForValue().get(exactKey);
            if (exactVal != null) {
                CachedQueryItem item = convertToCachedItem(exactVal);
                if (item != null) {
                    long latency = System.currentTimeMillis() - startTime;
                    recordHit(item.cachedResponse().estimatedTokensSaved(), latency);
                    log.info("Semantic Cache EXACT HIT for query '{}' in {}ms", query, latency);
                    return Optional.of(item.cachedResponse().withPerformanceMetrics(
                        true, latency, item.cachedResponse().estimatedTokensSaved(), "SEMANTIC_CACHE_EXACT"
                    ));
                }
            }

            // 2. Semantic Similarity Scan across active cache entries
            ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(cacheKeyPrefix + "*")
                .count(100)
                .build();

            Cursor<String> cursor = redisTemplate.scan(scanOptions);
            CachedQueryItem bestMatch = null;
            double maxSimilarity = -1.0;

            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key.endsWith(":exact")) {
                    continue; // Skip auxiliary exact match keys
                }
                Object val = redisTemplate.opsForValue().get(key);
                if (val != null) {
                    CachedQueryItem candidate = convertToCachedItem(val);
                    if (candidate != null && candidate.embedding() != null) {
                        double sim = GeminiClientService.cosineSimilarity(queryEmbedding, candidate.embedding());
                        if (sim > maxSimilarity) {
                            maxSimilarity = sim;
                            bestMatch = candidate;
                        }
                    }
                }
            }

            if (bestMatch != null && maxSimilarity >= threshold) {
                long latency = System.currentTimeMillis() - startTime;
                recordHit(bestMatch.cachedResponse().estimatedTokensSaved(), latency);
                log.info("Semantic Cache HIT for query '{}' (Cosine similarity: {}, threshold: {}) in {}ms", 
                    query, String.format("%.4f", maxSimilarity), threshold, latency);

                return Optional.of(bestMatch.cachedResponse().withPerformanceMetrics(
                    true, latency, bestMatch.cachedResponse().estimatedTokensSaved(), "SEMANTIC_CACHE_SIMILARITY"
                ));
            }

            // Cache Miss
            long latency = System.currentTimeMillis() - startTime;
            cacheMisses.incrementAndGet();
            log.debug("Semantic Cache MISS for query '{}' (Max similarity: {}) in {}ms", query, maxSimilarity, latency);
            return Optional.empty();

        } catch (Exception ex) {
            log.warn("Semantic Cache lookup failed or Redis is unavailable: {}. Falling back to DB search.", ex.getMessage());
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }

    /**
     * Stores query, embedding, and generated response into Redis Semantic Cache.
     */
    public void save(String query, float[] queryEmbedding, RagQueryResponse response) {
        try {
            CachedQueryItem item = new CachedQueryItem(
                query,
                queryEmbedding,
                response,
                System.currentTimeMillis()
            );

            String uniqueId = UUID.randomUUID().toString();
            String cacheKey = cacheKeyPrefix + uniqueId;
            String exactKey = buildExactKey(query);

            Duration ttl = Duration.ofSeconds(cacheTtlSeconds);

            // Store semantic entry
            redisTemplate.opsForValue().set(cacheKey, item, ttl);

            // Store exact shortcut entry
            redisTemplate.opsForValue().set(exactKey, item, ttl);

            log.debug("Saved semantic cache entry with key '{}', TTL {}s", cacheKey, cacheTtlSeconds);
        } catch (Exception ex) {
            log.warn("Failed to store query in Redis Semantic Cache: {}", ex.getMessage());
        }
    }

    public void recordMissLatency(long latencyMs) {
        totalCacheMissLatencyMs.addAndGet(latencyMs);
    }

    private void recordHit(int tokensSaved, long latencyMs) {
        cacheHits.incrementAndGet();
        totalTokensSaved.addAndGet(tokensSaved);
        totalCacheHitLatencyMs.addAndGet(latencyMs);
    }

    /**
     * Retrieves current semantic cache performance metrics.
     */
    public CacheStatsDto getStats() {
        long queries = totalQueries.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRatio = queries > 0 ? (double) hits / queries : 0.0;

        double avgCacheLatency = hits > 0 ? (double) totalCacheHitLatencyMs.get() / hits : 0.0;
        double avgDbLatency = misses > 0 ? (double) totalCacheMissLatencyMs.get() / misses : 0.0;

        return new CacheStatsDto(
            queries,
            hits,
            misses,
            Math.round(hitRatio * 1000.0) / 1000.0,
            Math.round(avgCacheLatency * 10.0) / 10.0,
            Math.round(avgDbLatency * 10.0) / 10.0,
            totalTokensSaved.get()
        );
    }

    /**
     * Flushes all semantic cache keys in Redis and resets metrics.
     */
    public void clearCache() {
        try {
            ScanOptions scanOptions = ScanOptions.scanOptions().match(cacheKeyPrefix + "*").build();
            Cursor<String> cursor = redisTemplate.scan(scanOptions);
            List<String> keysToDelete = new ArrayList<>();
            while (cursor.hasNext()) {
                keysToDelete.add(cursor.next());
            }
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
            }
            totalQueries.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
            totalTokensSaved.set(0);
            totalCacheHitLatencyMs.set(0);
            totalCacheMissLatencyMs.set(0);
            log.info("Cleared {} semantic cache entries from Redis", keysToDelete.size());
        } catch (Exception ex) {
            log.error("Failed to clear semantic cache: {}", ex.getMessage(), ex);
        }
    }

    private CachedQueryItem convertToCachedItem(Object raw) {
        if (raw instanceof CachedQueryItem item) {
            return item;
        }
        try {
            return objectMapper.convertValue(raw, CachedQueryItem.class);
        } catch (Exception e) {
            log.error("Failed to deserialize CachedQueryItem: {}", e.getMessage());
            return null;
        }
    }

    private String buildExactKey(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(query.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return cacheKeyPrefix + "exact:" + hexString;
        } catch (Exception ex) {
            return cacheKeyPrefix + "exact:" + query.trim().toLowerCase().hashCode();
        }
    }
}
