package com.haanh.ragassistant.service;

import com.haanh.ragassistant.dto.*;
import com.haanh.ragassistant.repository.ChunkSearchResultProjection;
import com.haanh.ragassistant.repository.ChunkVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RagOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestratorService.class);

    private final GeminiClientService geminiClientService;
    private final SemanticCacheService semanticCacheService;
    private final ChunkVectorRepository chunkVectorRepository;

    @Value("${rag.search.top-k:5}")
    private int defaultTopK;

    @Value("${rag.search.min-similarity:0.65}")
    private double defaultMinSimilarity;

    public RagOrchestratorService(
        GeminiClientService geminiClientService,
        SemanticCacheService semanticCacheService,
        ChunkVectorRepository chunkVectorRepository
    ) {
        this.geminiClientService = geminiClientService;
        this.semanticCacheService = semanticCacheService;
        this.chunkVectorRepository = chunkVectorRepository;
    }

    /**
     * Executes the end-to-end RAG pipeline:
     * 1. Vectorize query
     * 2. Check Redis Semantic Cache (<50ms fast path)
     * 3. If cache miss: Vector search Top-K in pgvector
     * 4. Build strict anti-hallucination prompt
     * 5. Call Gemini 2.5 Flash for JSON-structured answer
     * 6. Cache result in Redis and return
     */
    public RagQueryResponse processQuery(RagQueryRequest request) {
        long startTime = System.currentTimeMillis();
        String question = request.question().trim();
        int topK = request.effectiveTopK(defaultTopK);
        double minSimilarity = request.effectiveSimilarityThreshold(defaultMinSimilarity);

        log.info("Processing RAG query: '{}' (topK: {}, minSimilarity: {})", question, topK, minSimilarity);

        // Step 1: Generate embedding vector for the question
        float[] queryEmbedding = geminiClientService.generateEmbedding(question);

        // Step 2: Check Redis Semantic Cache
        Optional<RagQueryResponse> cachedResponse = semanticCacheService.lookup(
            question, queryEmbedding, request.similarityThreshold()
        );

        if (cachedResponse.isPresent()) {
            RagQueryResponse hit = cachedResponse.get();
            log.info("RAG Cache HIT! Responding in {}ms", hit.executionTimeMs());
            return hit;
        }

        log.info("RAG Cache MISS. Querying pgvector database...");

        // Step 3: pgvector Cosine Distance Search
        String vectorPgFormat = GeminiClientService.formatVectorForPostgres(queryEmbedding);
        List<ChunkSearchResultProjection> matchedChunks = chunkVectorRepository.searchSimilarChunks(
            vectorPgFormat, topK, minSimilarity
        );

        // If no relevant chunks meet the similarity threshold
        if (matchedChunks.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            semanticCacheService.recordMissLatency(duration);

            log.warn("No context chunks found above similarity threshold {} for query '{}'", minSimilarity, question);
            return new RagQueryResponse(
                "I don't have enough context in the provided documents to answer this question accurately.",
                List.of(),
                "LOW",
                false,
                duration,
                0,
                "PGVECTOR_NO_MATCH"
            );
        }

        // Step 4: Construct Grounded Context & System Prompt
        String contextBlock = buildContextBlock(matchedChunks);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(question, contextBlock);

        // Step 5: Call Gemini LLM with Strict JSON Output
        GeminiChatResponse llmOutput = geminiClientService.generateChatCompletion(systemPrompt, userPrompt);

        // Step 6: Map citations and correlate relevance scores from matched chunks
        List<CitationDto> citations = correlateCitations(llmOutput.citations(), matchedChunks);

        // Calculate estimated tokens saved for future cache hits (~4 chars per token)
        int estimatedTokensSaved = (systemPrompt.length() + userPrompt.length() + llmOutput.answer().length()) / 4;
        long totalExecutionTimeMs = System.currentTimeMillis() - startTime;
        semanticCacheService.recordMissLatency(totalExecutionTimeMs);

        RagQueryResponse response = new RagQueryResponse(
            llmOutput.answer(),
            citations,
            llmOutput.confidence(),
            false,
            totalExecutionTimeMs,
            estimatedTokensSaved,
            "HYBRID_RAG_LLM"
        );

        // Step 7: Save into Redis Semantic Cache (if confidence is MEDIUM or HIGH)
        if (!"LOW".equalsIgnoreCase(response.confidence())) {
            semanticCacheService.save(question, queryEmbedding, response);
        }

        log.info("Completed RAG query in {}ms with {} citations (Confidence: {})", 
            totalExecutionTimeMs, citations.size(), response.confidence());

        return response;
    }

    private String buildSystemPrompt() {
        return """
            You are an Enterprise Knowledge Assistant adhering to strict grounding and anti-hallucination protocols.
            
            GUIDELINES:
            1. Answer the user's question SOLELY using the factual excerpts provided in the Context below.
            2. Do NOT extrapolate, speculate, or introduce external knowledge not explicitly stated in the context.
            3. If the context does not contain sufficient factual evidence to answer the question, you MUST set answer to:
               "I don't have enough context in the provided documents to answer this question accurately."
               and set confidence to "LOW".
            4. For every claim made in your answer, you MUST cite the corresponding document name, chunk index, and direct excerpt.
            5. You MUST output ONLY valid JSON adhering strictly to this schema:
               {
                 "answer": "<thorough, factual answer>",
                 "citations": [
                   {
                     "documentName": "<name of source document>",
                     "chunkIndex": <integer index of chunk>,
                     "excerpt": "<direct sentence or phrase from context supporting the claim>"
                   }
                 ],
                 "confidence": "HIGH" | "MEDIUM" | "LOW"
               }
            """;
    }

    private String buildUserPrompt(String question, String contextBlock) {
        return String.format("""
            CONTEXT DOCUMENTS:
            %s
            
            USER QUESTION:
            %s
            
            Synthesize your grounded response in the required JSON format:
            """, contextBlock, question);
    }

    private String buildContextBlock(List<ChunkSearchResultProjection> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChunkSearchResultProjection chunk : chunks) {
            sb.append(String.format("--- [Document: %s | Chunk: %d | Relevance: %.3f] ---\n",
                chunk.getDocumentName(), chunk.getChunkIndex(), chunk.getSimilarity()));
            sb.append(chunk.getContent().trim()).append("\n\n");
        }
        return sb.toString();
    }

    private List<CitationDto> correlateCitations(
        List<GeminiCitationRaw> rawCitations,
        List<ChunkSearchResultProjection> matchedChunks
    ) {
        if (rawCitations == null || rawCitations.isEmpty()) {
            // Default to citing the top-ranked matched chunks
            return matchedChunks.stream()
                .limit(2)
                .map(c -> new CitationDto(
                    c.getDocumentName(),
                    c.getChunkIndex(),
                    extractBriefExcerpt(c.getContent()),
                    Math.round(c.getSimilarity() * 1000.0) / 1000.0
                ))
                .toList();
        }

        List<CitationDto> citations = new ArrayList<>();
        for (GeminiCitationRaw raw : rawCitations) {
            // Find corresponding similarity from matched chunks
            double score = matchedChunks.stream()
                .filter(m -> Objects.equals(m.getDocumentName(), raw.documentName()) 
                          && Objects.equals(m.getChunkIndex(), raw.chunkIndex()))
                .map(ChunkSearchResultProjection::getSimilarity)
                .findFirst()
                .orElse(0.85);

            citations.add(new CitationDto(
                raw.documentName(),
                raw.chunkIndex(),
                raw.excerpt(),
                Math.round(score * 1000.0) / 1000.0
            ));
        }

        return citations;
    }

    private String extractBriefExcerpt(String content) {
        if (content == null) return "";
        return content.length() > 200 ? content.substring(0, 197) + "..." : content;
    }
}
