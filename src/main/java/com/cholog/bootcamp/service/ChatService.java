package com.cholog.bootcamp.service;

import com.cholog.bootcamp.dto.ChatBotRequest;
import com.cholog.bootcamp.dto.ChatBotResponse;
import com.cholog.bootcamp.dto.TokenUsage;
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

    public ChatBotResponse sendMessage(ChatBotRequest chatBotRequest) {

        String question = chatBotRequest.question();
        String context = documentRetriever.retrieve(question);

        ChatResponse response = chatClient.prompt()
                .system("""
                        당신은 Cholog Corporation의 고객 상담 AI입니다.
                        아래 문서를 근거로 답변하세요. 문서에 없는 내용은 모른다고 답변하세요.

                        [참고 문서]
                        %s
                        """.formatted(context))
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
