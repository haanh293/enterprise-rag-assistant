package com.haanh.ragassistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiCitationRaw(
    String documentName,
    Integer chunkIndex,
    String excerpt
) {}
