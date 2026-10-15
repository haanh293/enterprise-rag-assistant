package com.haanh.ragassistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Response returned after uploading and ingesting a document")
public record DocumentUploadResponse(
    @Schema(description = "Unique identifier of the ingested document", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID documentId,

    @Schema(description = "Uploaded file name", example = "System_Architecture_2026.pdf")
    String filename,

    @Schema(description = "MIME content type of the file", example = "application/pdf")
    String contentType,

    @Schema(description = "File size in bytes", example = "2048576")
    long fileSize,

    @Schema(description = "Number of sliding-window chunks generated", example = "18")
    int totalChunks,

    @Schema(description = "Ingestion processing status", example = "PROCESSED")
    String status,

    @Schema(description = "Timestamp when the document was received")
    Instant uploadedAt
) {}
