package com.cholog.bootcamp.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class RagDocumentRetriever implements DocumentRetriever {

    private static final int TOP_K = 5;
    private static final Pattern H1_PATTERN = Pattern.compile("^# (.+)$", Pattern.MULTILINE);
    // ## 또는 ### 단위로 chunk 분할 (정책 문서는 ##, FAQ는 ### 사용)
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^(#{2,3}) (.+?)\\n(.*?)(?=^#{2,3} |\\z)", Pattern.MULTILINE | Pattern.DOTALL);

    private final SimpleVectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagDocumentRetriever(
            SimpleVectorStore vectorStore,
            @Value("${data.document-paths}") String[] documentPaths,
            @Value("${data.chatlog-path}") String chatlogPath,
            @Value("${data.vector-store-path}") String vectorStorePath
    ) {
        this.vectorStore = vectorStore;

        File storeFile = new File(vectorStorePath);
        if (storeFile.exists()) {
            vectorStore.load(storeFile);
        } else {
            List<Document> documents = new ArrayList<>();
            Arrays.stream(documentPaths)
                    .map(Path::of)
                    .flatMap(this::listMarkdownFiles)
                    .forEach(file -> documents.addAll(chunkMarkdown(file)));
            documents.addAll(loadCorrectChatLogs(Path.of(chatlogPath)));
            vectorStore.add(documents);
            vectorStore.save(storeFile);
        }
    }

    @Override
    public String retrieve(String query) {
        return vectorStore.similaritySearch(
                        SearchRequest.builder().query(query).topK(TOP_K).build()
                ).stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private Stream<Path> listMarkdownFiles(Path dir) {
        try {
            return Files.list(dir).filter(p -> p.toString().endsWith(".md")).sorted();
        } catch (IOException e) {
            throw new RuntimeException("디렉토리 읽기 실패: " + dir, e);
        }
    }

    private List<Document> chunkMarkdown(Path file) {
        List<Document> chunks = new ArrayList<>();
        try {
            String content = Files.readString(file);
            Matcher h1 = H1_PATTERN.matcher(content);
            String title = h1.find() ? h1.group(1).trim() : file.getFileName().toString();

            Matcher section = SECTION_PATTERN.matcher(content);
            while (section.find()) {
                String sectionTitle = section.group(2).trim();
                String body = section.group(3).trim();
                String text = "[" + title + "] " + sectionTitle + "\n" + body;
                chunks.add(new Document(text, Map.of("type", "policy", "source", file.getFileName().toString())));
            }
        } catch (IOException e) {
            throw new RuntimeException("파일 읽기 실패: " + file, e);
        }
        return chunks;
    }

    private List<Document> loadCorrectChatLogs(Path dir) {
        List<Document> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".jsonl")).sorted()
                    .forEach(file -> parseJsonlFile(file, chunks));
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 디렉토리 읽기 실패: " + dir, e);
        }
        return chunks;
    }

    private void parseJsonlFile(Path file, List<Document> chunks) {
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                JsonNode root = objectMapper.readTree(line);
                if (!"correct".equals(root.path("agent_accuracy").asText())) continue;

                String intent = root.path("primary_intent").asText();
                StringBuilder sb = new StringBuilder("[상담 이력: " + intent + "]\n");
                for (JsonNode turn : root.path("turns")) {
                    String role = "customer".equals(turn.path("role").asText()) ? "고객" : "상담원";
                    sb.append(role).append(": ").append(turn.path("text").asText()).append("\n");
                }
                chunks.add(new Document(sb.toString().trim(), Map.of("type", "chatlog")));
            }
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 파일 읽기 실패: " + file, e);
        }
    }
}
