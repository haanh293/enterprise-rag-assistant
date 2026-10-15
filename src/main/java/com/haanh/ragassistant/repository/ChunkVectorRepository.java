package com.haanh.ragassistant.repository;

import com.haanh.ragassistant.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkVectorRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * Approximate nearest neighbor vector search using pgvector cosine distance operator (<=>).
     * Calculates cosine similarity as (1 - distance) and filters by minimum similarity threshold.
     */
    @Query(value = """
        SELECT 
            c.id AS chunkId,
            c.document_id AS documentId,
            d.filename AS documentName,
            c.chunk_index AS chunkIndex,
            c.content AS content,
            (1.0 - (c.embedding <=> cast(:queryVector AS vector))) AS similarity
        FROM document_chunks c
        JOIN documents d ON c.document_id = d.id
        WHERE (1.0 - (c.embedding <=> cast(:queryVector AS vector))) >= :minSimilarity
        ORDER BY (c.embedding <=> cast(:queryVector AS vector)) ASC
        LIMIT :topK
        """, nativeQuery = true)
    List<ChunkSearchResultProjection> searchSimilarChunks(
        @Param("queryVector") String queryVector,
        @Param("topK") int topK,
        @Param("minSimilarity") double minSimilarity
    );

    /**
     * Native insert query to cast array string representation into pgvector 768-dim type.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO document_chunks (id, document_id, chunk_index, content, token_count, embedding, created_at)
        VALUES (:id, :documentId, :chunkIndex, :content, :tokenCount, cast(:embeddingStr AS vector), :createdAt)
        """, nativeQuery = true)
    void insertChunkWithVector(
        @Param("id") UUID id,
        @Param("documentId") UUID documentId,
        @Param("chunkIndex") int chunkIndex,
        @Param("content") String content,
        @Param("tokenCount") int tokenCount,
        @Param("embeddingStr") String embeddingStr,
        @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    @Query(value = "SELECT COUNT(*) FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    long countByDocumentId(@Param("documentId") UUID documentId);
}
