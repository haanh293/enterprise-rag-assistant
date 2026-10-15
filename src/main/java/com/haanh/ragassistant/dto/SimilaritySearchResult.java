package com.haanh.ragassistant.dto;

import java.util.UUID;

public record SimilaritySearchResult(
    UUID chunkId,
    UUID documentId,
    String documentName,
    Integer chunkIndex,
    String content,
    Double similarity
) {}
