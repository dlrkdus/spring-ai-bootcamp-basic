package com.cholog.bootcamp.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
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

    private static final int TOP_K = 7;
    private static final int HISTORICAL_TOP_K = 4;
    private static final MarkdownDocumentReaderConfig MARKDOWN_CONFIG = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(false)
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(false)
            .build();

    private final SimpleVectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Document> deprecatedDocuments = new ArrayList<>();

    public RagDocumentRetriever(
            SimpleVectorStore vectorStore,
            ChatClient chatClient,
            @Value("${data.document-paths}") String[] documentPaths,
            @Value("${data.chatlog-path}") String chatlogPath,
            @Value("${data.vector-store-path}") String vectorStorePath
    ) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;

        File storeFile = new File(vectorStorePath);
        if (storeFile.exists()) {
            vectorStore.load(storeFile);
            Arrays.stream(documentPaths)
                    .filter(p -> p.contains("deprecated"))
                    .flatMap(p -> listMarkdownFiles(Path.of(p)))
                    .forEach(file -> deprecatedDocuments.addAll(chunkMarkdown(file, "deprecated")));
        } else {
            List<Document> documents = new ArrayList<>();
            Arrays.stream(documentPaths).forEach(dirPath -> {
                String docType = dirPath.contains("faq") ? "faq"
                        : dirPath.contains("deprecated") ? "deprecated"
                        : "policy";
                List<Document> chunks = new ArrayList<>();
                listMarkdownFiles(Path.of(dirPath))
                        .forEach(file -> chunks.addAll(chunkMarkdown(file, docType)));
                if ("deprecated".equals(docType)) {
                    deprecatedDocuments.addAll(chunks);
                }
                documents.addAll(chunks);
            });
            documents.addAll(loadCorrectChatLogs(Path.of(chatlogPath)));
            vectorStore.add(documents);
            vectorStore.save(storeFile);
        }
    }

    @Override
    public String retrieve(String query) {
        List<Document> docs;

        if (isHistoricalQuery(query)) {
            // deprecated 전체 포함 + 현재 정책 상위 검색 → LLM이 직접 비교·판단
            List<Document> deprecated = getAllByType("deprecated");
            List<Document> current = searchExcludeType(query, "deprecated", HISTORICAL_TOP_K - 1);
            docs = new ArrayList<>();
            docs.addAll(deprecated);
            docs.addAll(current);
        } else {
            docs = searchExcludeType(query, "deprecated", TOP_K);
        }

        return docs.stream()
                .map(this::formatDoc)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<Document> getAllByType(String type) {
        if ("deprecated".equals(type)) {
            return deprecatedDocuments;
        }
        return List.of();
    }

    private List<Document> searchByType(String query, String type, int topK) {
        return vectorStore.similaritySearch(
                        SearchRequest.builder().query(query).topK(topK * 3).build()
                ).stream()
                .filter(doc -> type.equals(doc.getMetadata().get("type")))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private List<Document> searchExcludeType(String query, String excludeType, int topK) {
        return vectorStore.similaritySearch(
                        SearchRequest.builder().query(query).topK(topK * 3).build()
                ).stream()
                .filter(doc -> !excludeType.equals(doc.getMetadata().get("type")))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private boolean isHistoricalQuery(String query) {
        try {
            String result = chatClient.prompt()
                    .system("""
                            사용자의 질문을 분석해 'historical' 또는 'current' 중 하나만 답하세요. 다른 말은 절대 하지 마세요.

                            'historical'로 분류해야 하는 경우:
                            - 정책이 바뀌었는지, 달라졌는지, 변경됐는지 묻는 경우 (예: 변경됐어? 달라졌나요? 바뀐 거야?)
                            - 과거 정책이나 변경 이력을 묻는 경우
                            - 이전과 현재를 비교하는 경우
                            - 언제부터 이렇게 됐는지 묻는 경우

                            'current'로 분류해야 하는 경우:
                            - 현재 정책, 방법, 기준을 묻는 경우
                            - 일반적인 서비스 이용 문의
                            """)
                    .user(query)
                    .call()
                    .content();
            return "historical".equalsIgnoreCase(result.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private String formatDoc(Document doc) {
        String type = (String) doc.getMetadata().getOrDefault("type", "");
        String source = (String) doc.getMetadata().getOrDefault("source", "");
        String month = (String) doc.getMetadata().getOrDefault("month", "");

        return switch (type) {
            case "policy" -> "[정책문서: " + source + "]\n" + doc.getText();
            case "faq" -> "[FAQ: " + source + "]\n" + doc.getText();
            case "deprecated" -> "[과거정책문서: " + source + "]\n" + doc.getText();
            case "chatlog" -> {
                String monthSuffix = month.isEmpty() ? "" : " (" + month + ")";
                yield doc.getText().replaceFirst(
                        "(\\[상담 이력: [^\\]]+\\])",
                        "$1" + monthSuffix
                );
            }
            default -> doc.getText();
        };
    }

    private Stream<Path> listMarkdownFiles(Path dir) {
        try {
            return Files.list(dir).filter(p -> p.toString().endsWith(".md")).sorted();
        } catch (IOException e) {
            throw new RuntimeException("디렉토리 읽기 실패: " + dir, e);
        }
    }

    private List<Document> chunkMarkdown(Path file, String type) {
        String source = file.getFileName().toString();
        return new MarkdownDocumentReader(new FileSystemResource(file.toFile()), MARKDOWN_CONFIG)
                .get()
                .stream()
                .filter(doc -> !doc.getText().isBlank())
                .map(doc -> new Document(doc.getText(), Map.of("type", type, "source", source)))
                .collect(Collectors.toList());
    }

    private List<Document> loadCorrectChatLogs(Path dir) {
        List<Document> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".jsonl")).sorted()
                    .forEach(file -> {
                        String month = file.getFileName().toString().replace(".jsonl", "");
                        parseJsonlFile(file, chunks, month);
                    });
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 디렉토리 읽기 실패: " + dir, e);
        }
        return chunks;
    }

    private void parseJsonlFile(Path file, List<Document> chunks, String month) {
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
                chunks.add(new Document(sb.toString().trim(), Map.of("type", "chatlog", "month", month)));
            }
        } catch (IOException e) {
            throw new RuntimeException("채팅 로그 파일 읽기 실패: " + file, e);
        }
    }
}
