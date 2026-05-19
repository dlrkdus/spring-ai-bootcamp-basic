package com.cholog.bootcamp.dto;

public record ChatBotRequest(
        String question,
        String conversationId
) {

}
