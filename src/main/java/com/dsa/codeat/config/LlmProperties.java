package com.dsa.codeat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String provider = "openai";
    private String apiUrl;
    private String apiKey;
    private String model = "gpt-4o-mini";
    private long requestDelayMs = 2000;
    private Fallback fallback = new Fallback();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getRequestDelayMs() {
        return requestDelayMs;
    }

    public void setRequestDelayMs(long requestDelayMs) {
        this.requestDelayMs = requestDelayMs;
    }

    public Fallback getFallback() {
        return fallback;
    }

    public void setFallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public String resolvedApiUrl() {
        return resolveApiUrl(provider, apiUrl);
    }

    public String resolvedFallbackApiUrl() {
        return resolveApiUrl(fallback.provider, fallback.apiUrl);
    }

    private String resolveApiUrl(String providerName, String url) {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }

        String providerValue = providerName == null ? "" : providerName.trim().toLowerCase();
        return switch (providerValue) {
            case "groq" -> "https://api.groq.com/openai/v1/chat/completions";
            case "openai", "" -> "https://api.openai.com/v1/chat/completions";
            case "openrouter" -> "https://openrouter.ai/api/v1/chat/completions";
            case "cerebras" -> "https://api.cerebras.ai/v1/chat/completions";
            default -> throw new IllegalArgumentException("Unsupported llm.provider: " + providerName);
        };
    }

    public static class Fallback {
        private boolean enabled = false;
        private String provider = "cerebras";
        private String apiUrl;
        private String apiKey;
        private String model = "llama-3.3-70b";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
