package com.claims.ai.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal DeepSeek chat-completions client over the OpenAI-compatible wire
 * format ({@code POST {baseUrl}/chat/completions}). No SDK — plain
 * {@code java.net.http.HttpClient}, zero new Maven dependencies (same stance
 * as {@code S3PhotoStorage}).
 *
 * <p>Fail-closed: when no API key is configured (dev/test) or the provider is
 * disabled, {@link #available()} is false and {@link #completeJson} throws
 * {@link ProviderException} without touching the network.
 *
 * <p>Secrecy: the key, the prompt text, and claim PII are never logged.
 * Success logs carry only the model id, latency ms, and response length.
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_PREFIX = 200;

    private final DeepSeekProperties properties;
    private final HttpClient http;

    @Autowired
    public DeepSeekClient(DeepSeekProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }
    /** Package-visible constructor for unit tests (injectable HttpClient). */
    DeepSeekClient(DeepSeekProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** True only when the provider is enabled AND a non-blank key is configured. */
    public boolean available() {
        return properties.isEnabled()
                && properties.getApiKey() != null
                && !properties.getApiKey().isBlank();
    }

    /**
     * Completes a JSON-mode chat and returns the raw JSON string from
     * {@code choices[0].message.content}. Never returns null; every failure
     * (not configured, transport error, non-2xx, empty choices, blank content)
     * throws {@link ProviderException} with an ops-safe message.
     */
    public String completeJson(String systemPrompt, String userPrompt) throws ProviderException {
        if (systemPrompt == null || userPrompt == null) {
            throw new ProviderException("DeepSeek request failed: prompts must not be null");
        }
        if (!available()) {
            throw new ProviderException(
                    "DeepSeek provider is not configured (missing API key or disabled)");
        }

        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        String model = properties.getModel();
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 1500);
            payload.put("response_format", Map.of("type", "json_object"));
            body = JSON.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new ProviderException("DeepSeek request failed: could not encode request", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/chat/completions"))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ProviderException("DeepSeek request failed: transport error", ex);
        }
        long latencyMs = System.currentTimeMillis() - started;

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProviderException("DeepSeek request failed (status "
                    + response.statusCode() + "): " + bodyPrefix(response.body()));
        }

        String content = extractContent(response.body());
        if (content == null || content.isBlank()) {
            throw new ProviderException(
                    "DeepSeek returned an empty completion (model " + model + ")");
        }
        // Model id, latency, and length only — never the key, prompts, or PII.
        log.debug("DeepSeek completion model={} latencyMs={} responseLength={}",
                model, latencyMs, content.length());
        return content;
    }

    /** Parses {@code choices[0].message.content}; null when absent or not text. */
    private static String extractContent(String responseBody) throws ProviderException {
        try {
            JsonNode root = JSON.readTree(responseBody == null ? "" : responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                return null;
            }
            return content.asText();
        } catch (JacksonException ex) {
            throw new ProviderException(
                    "DeepSeek returned an unreadable response: " + bodyPrefix(responseBody), ex);
        }
    }

    /** Short ops-safe prefix of a response body for error messages. */
    private static String bodyPrefix(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_BODY_PREFIX
                ? singleLine
                : singleLine.substring(0, MAX_BODY_PREFIX) + "…";
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
