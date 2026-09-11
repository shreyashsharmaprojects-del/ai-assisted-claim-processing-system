package com.claims.ai.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the DeepSeek provider settings under {@code claims.ai}.
 *
 * <p>Values follow the repo's env-override style (see
 * {@code application.properties}): base URL, model, and key come from
 * {@code DEEPSEEK_BASE_URL} / {@code DEEPSEEK_MODEL} / {@code DEEPSEEK_API_KEY}
 * with safe dev defaults. A blank key is legal (dev/test) — the client then
 * fails closed via {@link DeepSeekClient#available()} instead of touching the
 * network.
 */
@ConfigurationProperties(prefix = "claims.ai")
public class DeepSeekProperties {

    /** Base URL of the OpenAI-compatible endpoint (no trailing path). */
    private String baseUrl = "https://api.deepseek.com";

    /** Model id sent in the chat-completions body. */
    private String model = "deepseek-flash";

    /** Bearer key; blank in dev/test. Never logged. */
    private String apiKey = "";

    /** Master switch; false disables the provider even with a key present. */
    private boolean enabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Redacts the key: toString never leaks it into logs or heap dumps. */
    @Override
    public String toString() {
        return "DeepSeekProperties{baseUrl='" + baseUrl + "', model='" + model
                + "', apiKey='[REDACTED]', enabled=" + enabled + "}";
    }
}
