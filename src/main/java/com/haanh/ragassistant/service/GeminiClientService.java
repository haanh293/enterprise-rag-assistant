package com.haanh.ragassistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haanh.ragassistant.dto.GeminiChatResponse;
import com.haanh.ragassistant.dto.GeminiCitationRaw;
import com.haanh.ragassistant.exception.GeminiApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

@Service
public class GeminiClientService {

    private static final Logger log = LoggerFactory.getLogger(GeminiClientService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.models.embedding:text-embedding-004}")
    private String embeddingModel;

    @Value("${gemini.models.chat:gemini-2.5-flash}")
    private String chatModel;

    public GeminiClientService(RestClient geminiRestClient, ObjectMapper objectMapper) {
        this.restClient = geminiRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates a 768-dimensional embedding vector for a single text chunk.
     */
    public float[] generateEmbedding(String text) {
        validateApiKey();
        String uri = String.format("/models/%s:embedContent?key=%s", embeddingModel, apiKey);

        Map<String, Object> contentPart = Map.of("text", text);
        Map<String, Object> contentObj = Map.of("parts", List.of(contentPart));
        Map<String, Object> requestPayload = Map.of(
            "model", "models/" + embeddingModel,
            "content", contentObj
        );

        try {
            String responseJson = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode valuesNode = root.path("embedding").path("values");

            if (valuesNode.isMissingNode() || !valuesNode.isArray()) {
                throw new GeminiApiException("Invalid response structure from Gemini Embedding API: " + responseJson);
            }

            float[] vector = new float[valuesNode.size()];
            for (int i = 0; i < valuesNode.size(); i++) {
                vector[i] = (float) valuesNode.get(i).asDouble();
            }
            return vector;
        } catch (RestClientResponseException ex) {
            log.error("Gemini Embedding API HTTP error: {} - {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new GeminiApiException("Gemini Embedding API error: " + ex.getResponseBodyAsString(), ex);
        } catch (JsonProcessingException ex) {
            throw new GeminiApiException("Failed to parse Gemini Embedding API JSON response", ex);
        } catch (Exception ex) {
            log.error("Unexpected error calling Gemini Embedding API: ", ex);
            throw new GeminiApiException("Unexpected error calling Gemini Embedding API: " + ex.getMessage(), ex);
        }
    }

    /**
     * Generates embedding vectors for a batch of text chunks using batchEmbedContents endpoint.
     */
    public List<float[]> generateBatchEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        validateApiKey();
        // Google Gemini supports up to 100 requests per batchEmbedContents call
        int batchSize = 50;
        List<float[]> results = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> sublist = texts.subList(i, end);
            List<float[]> batchVectors = executeBatchEmbedding(sublist);
            results.addAll(batchVectors);
        }

        return results;
    }

    private List<float[]> executeBatchEmbedding(List<String> texts) {
        String uri = String.format("/models/%s:batchEmbedContents?key=%s", embeddingModel, apiKey);

        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : texts) {
            requests.add(Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of("parts", List.of(Map.of("text", text)))
            ));
        }

        Map<String, Object> requestPayload = Map.of("requests", requests);

        try {
            String responseJson = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode embeddingsNode = root.path("embeddings");

            if (embeddingsNode.isMissingNode() || !embeddingsNode.isArray()) {
                throw new GeminiApiException("Invalid response structure from Gemini Batch Embedding API: " + responseJson);
            }

            List<float[]> vectors = new ArrayList<>();
            for (JsonNode itemNode : embeddingsNode) {
                JsonNode valuesNode = itemNode.path("values");
                float[] vector = new float[valuesNode.size()];
                for (int j = 0; j < valuesNode.size(); j++) {
                    vector[j] = (float) valuesNode.get(j).asDouble();
                }
                vectors.add(vector);
            }

            return vectors;
        } catch (Exception ex) {
            log.warn("Batch embedding failed, falling back to sequential single embeddings: {}", ex.getMessage());
            List<float[]> fallbackList = new ArrayList<>();
            for (String t : texts) {
                fallbackList.add(generateEmbedding(t));
            }
            return fallbackList;
        }
    }

    /**
     * Calls Gemini Chat Completion (gemini-2.5-flash) enforcing strict JSON output.
     */
    public GeminiChatResponse generateChatCompletion(String systemInstructionText, String userPrompt) {
        validateApiKey();
        String uri = String.format("/models/%s:generateContent?key=%s", chatModel, apiKey);

        Map<String, Object> systemPart = Map.of("text", systemInstructionText);
        Map<String, Object> systemInstructionObj = Map.of("parts", List.of(systemPart));

        Map<String, Object> userPart = Map.of("text", userPrompt);
        Map<String, Object> userContentObj = Map.of("role", "user", "parts", List.of(userPart));

        Map<String, Object> generationConfig = Map.of(
            "temperature", 0.1,
            "topP", 0.95,
            "responseMimeType", "application/json"
        );

        Map<String, Object> requestPayload = Map.of(
            "systemInstruction", systemInstructionObj,
            "contents", List.of(userContentObj),
            "generationConfig", generationConfig
        );

        try {
            String responseJson = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode candidateTextNode = root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text");

            if (candidateTextNode.isMissingNode()) {
                throw new GeminiApiException("Gemini model returned empty response: " + responseJson);
            }

            String rawJsonOutput = candidateTextNode.asText();
            log.debug("Gemini raw JSON output: {}", rawJsonOutput);

            return parseStructuredResponse(rawJsonOutput);

        } catch (RestClientResponseException ex) {
            log.error("Gemini Chat API HTTP error: {} - {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new GeminiApiException("Gemini Chat API error: " + ex.getResponseBodyAsString(), ex);
        } catch (JsonProcessingException ex) {
            throw new GeminiApiException("Failed to parse Gemini Chat response JSON", ex);
        } catch (Exception ex) {
            log.error("Unexpected error calling Gemini Chat API: ", ex);
            throw new GeminiApiException("Unexpected error calling Gemini Chat API: " + ex.getMessage(), ex);
        }
    }

    private GeminiChatResponse parseStructuredResponse(String rawJson) {
        try {
            // Remove markdown code fences if model accidentally wrapped json in ```json ... ```
            String cleanJson = rawJson.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            return objectMapper.readValue(cleanJson, GeminiChatResponse.class);
        } catch (Exception ex) {
            log.warn("Failed to parse strictly structured Gemini JSON output: {}. Fallback parsing.", ex.getMessage());
            return new GeminiChatResponse(
                rawJson,
                List.of(),
                "MEDIUM"
            );
        }
    }

    /**
     * Formats float[] vector into PostgreSQL pgvector format: "[0.123, -0.456, ...]"
     */
    public static String formatVectorForPostgres(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * High-speed in-memory cosine similarity calculation between two float vectors.
     */
    public static double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }

        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GeminiApiException("GEMINI_API_KEY is not configured. Please supply a valid Google Gemini API Key in .env or application.yml");
        }
    }
}
