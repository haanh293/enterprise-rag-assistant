package com.haanh.ragassistant.repository;

import java.util.UUID;

public interface ChunkSearchResultProjection {
    UUID getChunkId();
    UUID getDocumentId();
    String getDocumentName();
    Integer getChunkIndex();
    String getContent();
    Double getSimilarity();
}
