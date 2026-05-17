package com.cholog.bootcamp.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class ChatLogLoader {

    private final String cachedChatLogs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatLogLoader(@Value("${data.chatlog-path}") String chatlogPath) {
        this.cachedChatLogs = loadCorrectLogs(Path.of(chatlogPath));
    }

    public String load() {
        return cachedChatLogs;
    }

    private String loadCorrectLogs(Path dir) {
        List<String> conversations = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted()
                    .forEach(file -> parseFile(file, conversations));
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 디렉토리 읽기 실패: " + dir, e);
        }

        return String.join("\n\n", conversations);
    }

    private void parseFile(Path file, List<String> conversations) {
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                JsonNode root = objectMapper.readTree(line);

                if (!"correct".equals(root.path("agent_accuracy").asText())) continue;

                String intent = root.path("primary_intent").asText();
                StringBuilder sb = new StringBuilder();
                sb.append("[상담 유형: ").append(intent).append("]\n");

                for (JsonNode turn : root.path("turns")) {
                    String role = turn.path("role").asText();
                    String text = turn.path("text").asText();
                    sb.append("role".equals("customer") || "customer".equals(role) ? "고객: " : "상담원: ")
                      .append(text).append("\n");
                }

                conversations.add(sb.toString().trim());
            }
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 파일 읽기 실패: " + file, e);
        }
    }
}
