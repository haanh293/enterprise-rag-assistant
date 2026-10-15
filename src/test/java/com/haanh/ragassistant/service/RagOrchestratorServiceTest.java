package com.haanh.ragassistant.service;

import com.haanh.ragassistant.dto.*;
import com.haanh.ragassistant.repository.ChunkSearchResultProjection;
import com.haanh.ragassistant.repository.ChunkVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagOrchestratorServiceTest {

    @Mock
    private GeminiClientService geminiClientService;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private ChunkVectorRepository chunkVectorRepository;

    private RagOrchestratorService ragOrchestratorService;

    private final float[] dummyEmbedding = new float[]{0.1f, -0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        ragOrchestratorService = new RagOrchestratorService(
            geminiClientService,
            semanticCacheService,
            chunkVectorRepository
        );
        ReflectionTestUtils.setField(ragOrchestratorService, "defaultTopK", 5);
        ReflectionTestUtils.setField(ragOrchestratorService, "defaultMinSimilarity", 0.65);
    }

    @Test
    @DisplayName("Test 1: Cache Hit returns immediately from Redis Semantic Cache without querying DB or LLM")
    void testProcessQuery_CacheHit_ReturnsImmediatelyFromRedisCache() {
        // Arrange
        String question = "What is the encryption standard used for data at rest?";
        RagQueryRequest request = new RagQueryRequest(question, 5, 0.65);

        RagQueryResponse cachedResponse = new RagQueryResponse(
            "AES-256 is used for data at rest encryption.",
            List.of(new CitationDto("SecurityPolicy.pdf", 2, "AES-256 is used for data at rest", 0.94)),
            "HIGH",
            true,
            12L,
            650,
            "SEMANTIC_CACHE_SIMILARITY"
        );

        when(geminiClientService.generateEmbedding(question)).thenReturn(dummyEmbedding);
        when(semanticCacheService.lookup(eq(question), eq(dummyEmbedding), any())).thenReturn(Optional.of(cachedResponse));

        // Act
        RagQueryResponse actual = ragOrchestratorService.processQuery(request);

        // Assert
        assertThat(actual).isNotNull();
        assertThat(actual.cacheHit()).isTrue();
        assertThat(actual.answer()).isEqualTo("AES-256 is used for data at rest encryption.");
        assertThat(actual.source()).isEqualTo("SEMANTIC_CACHE_SIMILARITY");
        assertThat(actual.executionTimeMs()).isEqualTo(12L);

        // Verify fast-path: DB and LLM must NOT be called
        verify(chunkVectorRepository, never()).searchSimilarChunks(anyString(), anyInt(), anyDouble());
        verify(geminiClientService, never()).generateChatCompletion(anyString(), anyString());
    }

    @Test
    @DisplayName("Test 2: Cache Miss executes pgvector search, calls Gemini LLM, and caches result in Redis")
    void testProcessQuery_CacheMiss_VectorSearchAndLlmCalled_SavesToCache() {
        // Arrange
        String question = "How does the disaster recovery replication work?";
        RagQueryRequest request = new RagQueryRequest(question, null, null);

        ChunkSearchResultProjection mockChunk = createMockProjection(
            UUID.randomUUID(), UUID.randomUUID(), "Architecture.pdf", 4,
            "Disaster recovery replication runs across multiple AWS regions every 15 minutes.", 0.885
        );

        when(geminiClientService.generateEmbedding(question)).thenReturn(dummyEmbedding);
        when(semanticCacheService.lookup(eq(question), eq(dummyEmbedding), isNull())).thenReturn(Optional.empty());
        when(chunkVectorRepository.searchSimilarChunks(anyString(), eq(5), eq(0.65)))
            .thenReturn(List.of(mockChunk));

        GeminiChatResponse mockLlmResponse = new GeminiChatResponse(
            "Disaster recovery replicates data cross-region every 15 minutes.",
            List.of(new GeminiCitationRaw("Architecture.pdf", 4, "replicates data cross-region every 15 minutes")),
            "HIGH"
        );
        when(geminiClientService.generateChatCompletion(anyString(), anyString())).thenReturn(mockLlmResponse);

        // Act
        RagQueryResponse actual = ragOrchestratorService.processQuery(request);

        // Assert
        assertThat(actual).isNotNull();
        assertThat(actual.cacheHit()).isFalse();
        assertThat(actual.answer()).isEqualTo("Disaster recovery replicates data cross-region every 15 minutes.");
        assertThat(actual.confidence()).isEqualTo("HIGH");
        assertThat(actual.source()).isEqualTo("HYBRID_RAG_LLM");
        assertThat(actual.citations()).hasSize(1);
        assertThat(actual.citations().getFirst().documentName()).isEqualTo("Architecture.pdf");

        // Verify cache save and miss latency recording
        verify(semanticCacheService, times(1)).save(eq(question), eq(dummyEmbedding), any(RagQueryResponse.class));
        verify(semanticCacheService, times(1)).recordMissLatency(anyLong());
    }

    @Test
    @DisplayName("Test 3: Cache Miss with no matching chunks returns polite refusal without calling LLM")
    void testProcessQuery_CacheMiss_NoMatchingChunks_ReturnsPoliteRefusalWithoutCallingLLM() {
        // Arrange
        String question = "What is the secret recipe for Coca-Cola?";
        RagQueryRequest request = new RagQueryRequest(question, 5, 0.65);

        when(geminiClientService.generateEmbedding(question)).thenReturn(dummyEmbedding);
        when(semanticCacheService.lookup(eq(question), eq(dummyEmbedding), any())).thenReturn(Optional.empty());
        when(chunkVectorRepository.searchSimilarChunks(anyString(), anyInt(), anyDouble()))
            .thenReturn(Collections.emptyList());

        // Act
        RagQueryResponse actual = ragOrchestratorService.processQuery(request);

        // Assert
        assertThat(actual).isNotNull();
        assertThat(actual.cacheHit()).isFalse();
        assertThat(actual.confidence()).isEqualTo("LOW");
        assertThat(actual.answer()).contains("I don't have enough context in the provided documents");
        assertThat(actual.source()).isEqualTo("PGVECTOR_NO_MATCH");
        assertThat(actual.citations()).isEmpty();

        // Verify LLM is not called, and empty result is not cached
        verify(geminiClientService, never()).generateChatCompletion(anyString(), anyString());
        verify(semanticCacheService, never()).save(anyString(), any(), any());
    }

    @Test
    @DisplayName("Test 4: Cache Miss with LOW confidence answer is NOT saved to Redis Semantic Cache")
    void testProcessQuery_CacheMiss_LowConfidenceAnswer_DoesNotSaveToCache() {
        // Arrange
        String question = "Can employees use external AI tools?";
        RagQueryRequest request = new RagQueryRequest(question, 5, 0.65);

        ChunkSearchResultProjection mockChunk = createMockProjection(
            UUID.randomUUID(), UUID.randomUUID(), "EmployeeHandbook.pdf", 1,
            "General IT policy outline.", 0.66
        );

        when(geminiClientService.generateEmbedding(question)).thenReturn(dummyEmbedding);
        when(semanticCacheService.lookup(eq(question), eq(dummyEmbedding), any())).thenReturn(Optional.empty());
        when(chunkVectorRepository.searchSimilarChunks(anyString(), anyInt(), anyDouble()))
            .thenReturn(List.of(mockChunk));

        GeminiChatResponse lowConfidenceLlmResponse = new GeminiChatResponse(
            "I don't have enough context in the provided documents to answer this question accurately.",
            List.of(),
            "LOW"
        );
        when(geminiClientService.generateChatCompletion(anyString(), anyString())).thenReturn(lowConfidenceLlmResponse);

        // Act
        RagQueryResponse actual = ragOrchestratorService.processQuery(request);

        // Assert
        assertThat(actual.confidence()).isEqualTo("LOW");
        // Verify low confidence answer is never cached to prevent polluting the semantic cache
        verify(semanticCacheService, never()).save(anyString(), any(), any());
    }

    @Test
    @DisplayName("Test 5: Custom topK and similarityThreshold from request are respected by vector search")
    void testProcessQuery_CustomTopKAndSimilarityThresholdPassedDown() {
        // Arrange
        String question = "Detail the SLA commitments.";
        int customTopK = 10;
        double customThreshold = 0.82;
        RagQueryRequest request = new RagQueryRequest(question, customTopK, customThreshold);

        ChunkSearchResultProjection mockChunk = createMockProjection(
            UUID.randomUUID(), UUID.randomUUID(), "SLA_Document.pdf", 0,
            "99.99% monthly availability commitment.", 0.89
        );

        when(geminiClientService.generateEmbedding(question)).thenReturn(dummyEmbedding);
        when(semanticCacheService.lookup(eq(question), eq(dummyEmbedding), eq(customThreshold))).thenReturn(Optional.empty());
        when(chunkVectorRepository.searchSimilarChunks(anyString(), eq(customTopK), eq(customThreshold)))
            .thenReturn(List.of(mockChunk));

        GeminiChatResponse mockResponse = new GeminiChatResponse(
            "The platform guarantees 99.99% monthly availability.",
            List.of(new GeminiCitationRaw("SLA_Document.pdf", 0, "99.99% monthly availability")),
            "HIGH"
        );
        when(geminiClientService.generateChatCompletion(anyString(), anyString())).thenReturn(mockResponse);

        // Act
        RagQueryResponse actual = ragOrchestratorService.processQuery(request);

        // Assert
        assertThat(actual.answer()).isEqualTo("The platform guarantees 99.99% monthly availability.");
        verify(chunkVectorRepository, times(1)).searchSimilarChunks(anyString(), eq(10), eq(0.82));
    }

    private ChunkSearchResultProjection createMockProjection(
        UUID chunkId, UUID docId, String docName, int index, String content, double similarity
    ) {
        return new ChunkSearchResultProjection() {
            @Override public UUID getChunkId() { return chunkId; }
            @Override public UUID getDocumentId() { return docId; }
            @Override public String getDocumentName() { return docName; }
            @Override public Integer getChunkIndex() { return index; }
            @Override public String getContent() { return content; }
            @Override public Double getSimilarity() { return similarity; }
        };
    }
}
