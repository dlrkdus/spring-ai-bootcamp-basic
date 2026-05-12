package com.cholog.bootcamp.service;

import com.cholog.bootcamp.dto.ChatBotRequest;
import com.cholog.bootcamp.dto.ChatBotResponse;
import com.cholog.bootcamp.dto.TokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService {

    // ChatClient 는 어떤 동작까지 할 수 있는가?
    private final ChatClient chatClient;

    // TODO 지금은 단순 질의응답 구조, 상담 데이터를 어디에 적재해서 어떻게 활용할건지?
    public ChatBotResponse sendMessage(ChatBotRequest chatBotRequest) {

        String question = chatBotRequest.question();

        ChatResponse response = chatClient.prompt()
                .user(question)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null) {
            // TODO 응답이 안 온다면 어떻게 처리?
            throw new RuntimeException();
        }

        String answer = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();

        TokenUsage tokenUsage = TokenUsage.from(
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()
        );

        return ChatBotResponse.from(answer, tokenUsage);
    }
}
