package com.cholog.bootcamp.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class RagDocumentRetriever implements DocumentRetriever {

    private static final int TOP_K = 5;
    private static final MarkdownDocumentReaderConfig MARKDOWN_CONFIG = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(false)
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(false)
            .build();

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
        String source = file.getFileName().toString();
        return new MarkdownDocumentReader(new FileSystemResource(file.toFile()), MARKDOWN_CONFIG)
                .get()
                .stream()
                .filter(doc -> !doc.getText().isBlank())
                .map(doc -> new Document(doc.getText(), Map.of("type", "policy", "source", source)))
                .collect(Collectors.toList());
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
