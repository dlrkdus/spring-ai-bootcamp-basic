package com.cholog.bootcamp.service;

import com.cholog.bootcamp.dto.ChatBotRequest;
import com.cholog.bootcamp.dto.ChatBotResponse;
import com.cholog.bootcamp.dto.TokenUsage;
import com.cholog.bootcamp.retriever.DocumentRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final DocumentRetriever documentRetriever;
    private final ChatMemory chatMemory;

    private String buildSystemPrompt(String context) {
        return """
                당신은 Cholog Corporation의 고객 상담 AI입니다.
                아래 참고 자료를 바탕으로 고객 질문에 정확하고 친절하게 답변하세요.
                참고 자료에 없는 내용은 "해당 정보를 확인할 수 없습니다"라고 답하세요.

                우선순위 규칙:
                1. [정책문서]가 가장 권위 있는 현행 자료입니다. 다른 자료와 충돌하면 항상 [정책문서]를 따르세요.
                2. [FAQ]는 공식 안내이지만 [정책문서]와 충돌하면 [정책문서]를 따르세요.
                3. [과거정책문서]는 더 이상 유효하지 않은 이전 정책입니다. 현재 정책을 묻는 질문에는 사용하지 마세요. 과거 정책을 묻는 질문에만 참고하세요.
                4. [상담 이력]은 보조 참고 자료입니다. 날짜가 표시된 경우, 해당 시점의 정책을 기준으로 한 답변이므로 현재 정책과 다를 수 있습니다.
                5. [상담 이력]은 [정책문서]나 [FAQ]보다 우선할 수 없습니다.

                [참고 자료]
                %s
                """.formatted(context);
    }

    public Flux<String> streamMessage(ChatBotRequest request) {
        String context = documentRetriever.retrieve(request.question());
        var prompt = chatClient.prompt()
                .system(buildSystemPrompt(context))
                .user(request.question());

        if (request.conversationId() != null) {
            prompt = prompt.advisors(
                    MessageChatMemoryAdvisor.builder(chatMemory)
                            .conversationId(request.conversationId())
                            .build()
            );
        }

        return prompt.stream().content();
    }

    public ChatBotResponse sendMessage(ChatBotRequest chatBotRequest) {
        String question = chatBotRequest.question();
        String context = documentRetriever.retrieve(question);

        ChatResponse response = chatClient.prompt()
                .system(buildSystemPrompt(context))
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
