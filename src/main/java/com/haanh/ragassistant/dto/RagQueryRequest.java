package com.haanh.ragassistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for querying the Enterprise RAG system")
public record RagQueryRequest(
    @Schema(description = "Natural language question from user", example = "What are the key security features of our enterprise platform?")
    @NotBlank(message = "Question must not be blank")
    @Size(min = 3, max = 2000, message = "Question must be between 3 and 2000 characters")
    String question,

    @Schema(description = "Maximum number of relevant chunks to retrieve (default: 5)", example = "5")
    Integer topK,

    @Schema(description = "Cosine similarity threshold (0.0 to 1.0) for context relevance (default: 0.65)", example = "0.65")
    Double similarityThreshold
) {
    public int effectiveTopK(int defaultTopK) {
        return (topK != null && topK > 0) ? topK : defaultTopK;
    }

    public double effectiveSimilarityThreshold(double defaultThreshold) {
        return (similarityThreshold != null && similarityThreshold >= 0.0 && similarityThreshold <= 1.0)
            ? similarityThreshold : defaultThreshold;
    }
}
