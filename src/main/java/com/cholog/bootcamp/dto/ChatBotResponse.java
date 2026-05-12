package com.cholog.bootcamp.dto;

import lombok.Builder;

@Builder
public record ChatBotResponse(
        String answer,
        TokenUsage tokenUsage
) {
    public static ChatBotResponse from(String answer, TokenUsage tokenUsage) {
        return ChatBotResponse.builder()
                .answer(answer)
                .tokenUsage(tokenUsage)
                .build();
    }
}
