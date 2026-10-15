package com.haanh.ragassistant.dto;

import java.io.Serializable;

public record CachedQueryItem(
    String originalQuestion,
    float[] embedding,
    RagQueryResponse cachedResponse,
    long timestamp
) implements Serializable {}
