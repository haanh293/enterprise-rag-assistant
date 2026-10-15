package com.haanh.ragassistant.service;

import com.haanh.ragassistant.dto.DocumentUploadResponse;
import com.haanh.ragassistant.entity.DocumentChunk;
import com.haanh.ragassistant.entity.DocumentMetadata;
import com.haanh.ragassistant.entity.DocumentStatus;
import com.haanh.ragassistant.exception.DocumentProcessingException;
import com.haanh.ragassistant.repository.ChunkVectorRepository;
import com.haanh.ragassistant.repository.DocumentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentRepository documentRepository;
    private final ChunkVectorRepository chunkVectorRepository;
    private final GeminiClientService geminiClientService;

    @Value("${rag.chunking.target-chunk-size:1800}")
    private int targetChunkSize;

    @Value("${rag.chunking.chunk-overlap:200}")
    private int chunkOverlap;

    public DocumentIngestionService(
        DocumentRepository documentRepository,
        ChunkVectorRepository chunkVectorRepository,
        GeminiClientService geminiClientService
    ) {
        this.documentRepository = documentRepository;
        this.chunkVectorRepository = chunkVectorRepository;
        this.geminiClientService = geminiClientService;
    }

    /**
     * Ingests, parses, chunks, embeds, and persists a document into PostgreSQL pgvector.
     */
    @Transactional
    public DocumentUploadResponse ingestDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentProcessingException("Uploaded file cannot be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unnamed_document_" + System.currentTimeMillis();
        }

        String contentType = file.getContentType();
        if (contentType == null) {
            contentType = detectContentType(originalFilename);
        }

        long fileSize = file.getSize();
        log.info("Starting ingestion for file: '{}', size: {} bytes, type: '{}'", originalFilename, fileSize, contentType);

        // 1. Extract raw text from file
        String rawText = extractText(file, contentType, originalFilename);
        if (rawText.isBlank()) {
            throw new DocumentProcessingException("No readable text found in document: " + originalFilename);
        }

        // 2. Perform Sliding-Window Chunking with Overlap
        List<String> textChunks = splitIntoSlidingWindowChunks(rawText, targetChunkSize, chunkOverlap);
        log.info("Document '{}' split into {} chunks (window: {}, overlap: {})", originalFilename, textChunks.size(), targetChunkSize, chunkOverlap);

        // 3. Save initial DocumentMetadata record
        DocumentMetadata metadata = new DocumentMetadata(originalFilename, contentType, fileSize);
        metadata.setTotalChunks(textChunks.size());
        metadata.setStatus(DocumentStatus.PROCESSING);
        metadata = documentRepository.save(metadata);

        UUID documentId = metadata.getId();

        try {
            // 4. Batch vectorize chunks using Google Gemini text-embedding-004
            List<float[]> embeddings = geminiClientService.generateBatchEmbeddings(textChunks);
            if (embeddings.size() != textChunks.size()) {
                throw new DocumentProcessingException("Mismatch between chunk count (" + textChunks.size() 
                    + ") and embedding count (" + embeddings.size() + ")");
            }

            // 5. Persist chunks with pgvector embeddings into database
            for (int i = 0; i < textChunks.size(); i++) {
                String chunkText = textChunks.get(i);
                float[] embedding = embeddings.get(i);
                int tokenCount = estimateTokenCount(chunkText);
                String embeddingPgFormat = GeminiClientService.formatVectorForPostgres(embedding);

                chunkVectorRepository.insertChunkWithVector(
                    UUID.randomUUID(),
                    documentId,
                    i,
                    chunkText,
                    tokenCount,
                    embeddingPgFormat,
                    Instant.now()
                );
            }

            // 6. Update document status to PROCESSED
            metadata.setStatus(DocumentStatus.PROCESSED);
            metadata = documentRepository.save(metadata);

            log.info("Successfully ingested document '{}' with {} vector chunks into pgvector", originalFilename, textChunks.size());

            return new DocumentUploadResponse(
                metadata.getId(),
                metadata.getFilename(),
                metadata.getContentType(),
                metadata.getFileSize(),
                metadata.getTotalChunks(),
                metadata.getStatus().name(),
                metadata.getCreatedAt()
            );

        } catch (Exception ex) {
            log.error("Failed to process document '{}': {}", originalFilename, ex.getMessage(), ex);
            metadata.setStatus(DocumentStatus.FAILED);
            documentRepository.save(metadata);
            throw new DocumentProcessingException("Failed to process document: " + ex.getMessage(), ex);
        }
    }

    /**
     * Sliding-window text chunking algorithm with character overlap.
     * Ensures sentences/words are kept intact where possible.
     */
    public List<String> splitIntoSlidingWindowChunks(String text, int targetSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Normalize line breaks and duplicate spaces
        String cleanText = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n").trim();
        int textLength = cleanText.length();

        if (textLength <= targetSize) {
            chunks.add(cleanText);
            return chunks;
        }

        int step = targetSize - overlap;
        if (step <= 0) {
            step = targetSize / 2;
        }

        int start = 0;
        while (start < textLength) {
            int end = Math.min(start + targetSize, textLength);

            // Attempt to snap 'end' to the end of a sentence or newline if not at the end of the text
            if (end < textLength) {
                int adjustedEnd = findNaturalBreakPoint(cleanText, start + step, end);
                if (adjustedEnd > start) {
                    end = adjustedEnd;
                }
            }

            String chunk = cleanText.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= textLength) {
                break;
            }

            // Slide window forward
            start += step;
        }

        return chunks;
    }

    private int findNaturalBreakPoint(String text, int minSearchIndex, int maxSearchIndex) {
        // Search backwards from maxSearchIndex for period, question mark, exclamation, or newline
        for (int i = maxSearchIndex; i >= minSearchIndex; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '.' || c == '?' || c == '!') {
                return i;
            }
        }
        // Fallback to space
        for (int i = maxSearchIndex; i >= minSearchIndex; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return maxSearchIndex;
    }

    private String extractText(MultipartFile file, String contentType, String filename) {
        try {
            byte[] bytes = file.getBytes();
            String lowerFilename = filename.toLowerCase();

            if (contentType.contains("pdf") || lowerFilename.endsWith(".pdf")) {
                return extractPdfText(bytes);
            } else if (contentType.contains("markdown") || lowerFilename.endsWith(".md")) {
                return new String(bytes, StandardCharsets.UTF_8);
            } else {
                // Default to UTF-8 text (TXT, CSV, etc.)
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new DocumentProcessingException("Failed to read file bytes: " + ex.getMessage(), ex);
        }
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException ex) {
            throw new DocumentProcessingException("Failed to parse PDF document: " + ex.getMessage(), ex);
        }
    }

    private String detectContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /**
     * Approximate token count (rule of thumb: ~4 characters per token in English/mixed languages).
     */
    public int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 4);
    }
}
