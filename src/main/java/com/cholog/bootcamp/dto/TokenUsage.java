package com.cholog.bootcamp.dto;

import lombok.Builder;

@Builder
public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
    public static TokenUsage from(int promptTokens, int completionTokens, int totalTokens) {
        return TokenUsage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .build();
    }
}
