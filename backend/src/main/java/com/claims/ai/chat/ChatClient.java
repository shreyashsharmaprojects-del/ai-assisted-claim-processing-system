package com.claims.ai.chat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.claims.ai.client.DeepSeekProperties;
import com.claims.ai.client.ProviderException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plain-text chat client for the AI adjuster chat box.
 *
 * <p>Why a second client instead of reusing {@code DeepSeekClient}: that
 * client forces {@code response_format: json_object} (right for the
 * DECISION advisory, wrong here — the adjuster wants free-text answers).
 * {@code DeepSeekClient} lives outside this package and must not be
 * modified, so this component reuses only {@link DeepSeekProperties} and
 * mirrors the sibling's wire patterns: plain
 * {@code java.net.http.HttpClient}, {@code POST {base}/chat/completions},
 * 5s connect / 30s read timeouts, Bearer auth, temperature 0.2, and
 * secrecy logging (model + latency + length only — never the key, prompts,
 * or claim PII).
 *
 * <p>Differences from the JSON client: {@code max_tokens} 800 and NO
 * {@code response_format} (plain-text answer expected).
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_PREFIX = 200;

    /** Defense in depth: the model never sees more than 10 history pairs. */
    static final int MAX_HISTORY_PAIRS = 10;
    /** Defense in depth: every history entry is truncated to 1000 chars. */
    static final int MAX_HISTORY_CHARS = 1000;

    private final DeepSeekProperties properties;
    private final HttpClient http;

    @Autowired
    public ChatClient(DeepSeekProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    /** Package-visible constructor for unit tests (injectable HttpClient). */
    ChatClient(DeepSeekProperties properties, HttpClient http) {
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
     * Asks the provider a plain-text question with prior conversation for
     * context. {@code history} holds prior {@code {role, content}} pairs
     * (role {@code "user"} or {@code "assistant"}, already validated by the
     * caller); this method additionally caps it at {@link #MAX_HISTORY_PAIRS}
     * pairs and truncates every entry to {@link #MAX_HISTORY_CHARS} chars so
     * the model can never see unbounded context.
     *
     * @return the raw text of {@code choices[0].message.content}, never null
     * @throws ProviderException on every failure (not configured, transport
     *         error, non-2xx, empty choices, blank content)
     */
    public String ask(String systemPrompt, String userPrompt,
            List<Map<String, String>> history) throws ProviderException {
        if (systemPrompt == null || userPrompt == null) {
            throw new ProviderException("AI chat request failed: prompts must not be null");
        }
        if (!available()) {
            throw new ProviderException(
                    "AI chat provider is not configured (missing API key or disabled)");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            int from = Math.max(0, history.size() - MAX_HISTORY_PAIRS);
            for (Map<String, String> entry : history.subList(from, history.size())) {
                if (entry == null) {
                    continue;
                }
                String role = entry.get("role");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                String content = entry.get("content");
                messages.add(Map.of("role", role, "content", truncate(content)));
            }
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        String model = properties.getModel();
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 800);
            body = JSON.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new ProviderException("AI chat request failed: could not encode request", ex);
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
            throw new ProviderException("AI chat request failed: transport error", ex);
        }
        long latencyMs = System.currentTimeMillis() - started;

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProviderException("AI chat request failed (status "
                    + response.statusCode() + "): " + bodyPrefix(response.body()));
        }

        String content = extractContent(response.body());
        if (content == null || content.isBlank()) {
            throw new ProviderException(
                    "AI chat returned an empty completion (model " + model + ")");
        }
        // Model id, latency, and length only — never the key, prompts, or PII.
        log.debug("AI chat completion model={} latencyMs={} responseLength={}",
                model, latencyMs, content.length());
        return content;
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_HISTORY_CHARS ? content
                : content.substring(0, MAX_HISTORY_CHARS);
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
                    "AI chat returned an unreadable response: " + bodyPrefix(responseBody), ex);
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
