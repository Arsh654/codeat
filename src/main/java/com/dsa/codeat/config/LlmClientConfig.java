package com.dsa.codeat.config;

import com.dsa.codeat.service.LlmScoringClient;
import com.dsa.codeat.service.OpenAiLlmScoringClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientConfig {

    @Bean
    LlmScoringClient llmScoringClient(LlmProperties llmProperties) {
        if (StringUtils.hasText(llmProperties.getApiKey())) {
            return new OpenAiLlmScoringClient(llmProperties);
        }
        return request -> {
            throw new IllegalStateException("LLM client is not configured. Set llm.api-key in application.yml or env vars.");
        };
    }
}
