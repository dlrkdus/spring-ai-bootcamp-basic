package com.cholog.bootcamp.service;

import com.cholog.bootcamp.dto.ChatBotRequest;
import com.cholog.bootcamp.dto.ChatBotResponse;
import com.cholog.bootcamp.dto.TokenUsage;
import com.cholog.bootcamp.retriever.ChatLogLoader;
import com.cholog.bootcamp.retriever.DocumentRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final DocumentRetriever documentRetriever;
    private final ChatLogLoader chatLogLoader;

    public ChatBotResponse sendMessage(ChatBotRequest chatBotRequest) {

        String question = chatBotRequest.question();
        String policyContext = documentRetriever.retrieve(question);
        String chatLogContext = chatLogLoader.load();

        ChatResponse response = chatClient.prompt()
                .system("""
                        당신은 Cholog Corporation의 고객 상담 AI입니다.

                        [현재 정책 문서] — 항상 이 내용을 최우선으로 따르세요.
                        문서에 없는 내용은 모른다고 답변하세요.
                        %s

                        [상담 이력 예시] — 응대 스타일 참고용입니다.
                        정책 문서와 충돌하는 내용이 있으면 반드시 정책 문서를 따르세요.
                        %s
                        """.formatted(policyContext, chatLogContext))
                .user(question)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null) {
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
