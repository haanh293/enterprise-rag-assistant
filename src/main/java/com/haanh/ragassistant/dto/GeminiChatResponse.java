package com.haanh.ragassistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiChatResponse(
    String answer,
    List<GeminiCitationRaw> citations,
    String confidence
) {
    public GeminiChatResponse {
        if (citations == null) {
            citations = List.of();
        }
        if (confidence == null || confidence.isBlank()) {
            confidence = "MEDIUM";
        }
    }
}
