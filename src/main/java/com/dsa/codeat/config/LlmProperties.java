package com.dsa.codeat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String provider = "openai";
    private String apiUrl;
    private String apiKey;
    private String model = "gpt-4o-mini";

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

    public String resolvedApiUrl() {
        if (apiUrl != null && !apiUrl.isBlank()) {
            return apiUrl.trim();
        }

        String providerValue = provider == null ? "" : provider.trim().toLowerCase();
        return switch (providerValue) {
            case "groq" -> "https://api.groq.com/openai/v1/chat/completions";
            case "openai", "" -> "https://api.openai.com/v1/chat/completions";
            default -> throw new IllegalArgumentException("Unsupported llm.provider: " + provider);
        };
    }
}
