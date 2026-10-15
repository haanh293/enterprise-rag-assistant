package com.haanh.ragassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentMetadata document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public DocumentChunk() {
    }

    public DocumentChunk(DocumentMetadata document, Integer chunkIndex, String content, Integer tokenCount) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.tokenCount = tokenCount;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public DocumentMetadata getDocument() {
        return document;
    }

    public void setDocument(DocumentMetadata document) {
        this.document = document;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentChunk that = (DocumentChunk) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
