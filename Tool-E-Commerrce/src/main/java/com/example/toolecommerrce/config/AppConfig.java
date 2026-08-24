package com.example.toolecommerrce.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${telegram.bot.api-url}")
    private String telegramApiUrl;

    @Value("${telegram.bot.token}")
    private String telegramBotToken;

    @Bean("geminiWebClient")
    public WebClient geminiWebClient() {
        return WebClient.builder()
                .baseUrl(geminiApiUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean("telegramWebClient")
    public WebClient telegramWebClient() {
        return WebClient.builder()
                .baseUrl(telegramApiUrl + telegramBotToken)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
