package com.haanh.ragassistant.controller;

import com.haanh.ragassistant.dto.DocumentUploadResponse;
import com.haanh.ragassistant.entity.DocumentMetadata;
import com.haanh.ragassistant.exception.ResourceNotFoundException;
import com.haanh.ragassistant.repository.ChunkVectorRepository;
import com.haanh.ragassistant.repository.DocumentRepository;
import com.haanh.ragassistant.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Document Ingestion", description = "Endpoints for uploading, parsing, chunking, and managing enterprise knowledge documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;
    private final DocumentRepository documentRepository;
    private final ChunkVectorRepository chunkVectorRepository;

    public DocumentController(
        DocumentIngestionService documentIngestionService,
        DocumentRepository documentRepository,
        ChunkVectorRepository chunkVectorRepository
    ) {
        this.documentIngestionService = documentIngestionService;
        this.documentRepository = documentRepository;
        this.chunkVectorRepository = chunkVectorRepository;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Upload and Ingest Document",
        description = "Accepts PDF, Markdown, or TXT file, splits content into sliding-window chunks with overlap, generates 768-dim embeddings via Gemini API, and stores into PostgreSQL pgvector.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Document processed and indexed successfully", content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid file or empty payload"),
            @ApiResponse(responseCode = "422", description = "Failed to parse document text or generate embeddings")
        }
    )
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
        @Parameter(description = "Document file to ingest (PDF, MD, or TXT)", required = true)
        @RequestParam("file") MultipartFile file
    ) {
        DocumentUploadResponse response = documentIngestionService.ingestDocument(file);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    @Operation(summary = "List All Documents", description = "Returns metadata for all ingested documents in descending order of creation")
    public ResponseEntity<List<DocumentMetadata>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Document Details", description = "Retrieves metadata and processing status for a specific document ID")
    public ResponseEntity<DocumentMetadata> getDocument(
        @Parameter(description = "Document UUID", required = true)
        @PathVariable UUID id
    ) {
        DocumentMetadata metadata = documentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + id));
        return ResponseEntity.ok(metadata);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @Operation(summary = "Delete Document", description = "Deletes a document and cascades deletion to all its vector chunks")
    public ResponseEntity<Map<String, Object>> deleteDocument(
        @Parameter(description = "Document UUID to delete", required = true)
        @PathVariable UUID id
    ) {
        if (!documentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Document not found with ID: " + id);
        }
        chunkVectorRepository.deleteByDocumentId(id);
        documentRepository.deleteById(id);
        return ResponseEntity.ok(Map.of(
            "message", "Document and associated vector chunks deleted successfully",
            "documentId", id
        ));
    }
}
