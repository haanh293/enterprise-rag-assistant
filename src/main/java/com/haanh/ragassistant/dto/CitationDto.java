package com.haanh.ragassistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Citation information pointing to source document chunk")
public record CitationDto(
    @Schema(description = "Source document file name", example = "Security_Architecture_2026.pdf")
    String documentName,

    @Schema(description = "Zero-based chunk index within the document", example = "3")
    Integer chunkIndex,

    @Schema(description = "Direct excerpt quote from the document supporting the answer", example = "All customer data at rest is encrypted using AES-256-GCM.")
    String excerpt,

    @Schema(description = "Cosine relevance score (0.0 to 1.0)", example = "0.892")
    Double relevanceScore
) {}
