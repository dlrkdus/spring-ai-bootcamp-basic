package com.cholog.bootcamp.retriever;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class FullContextDocumentRetriever implements DocumentRetriever {

    private final String cachedContext;

    public FullContextDocumentRetriever(
            @Value("${data.document-paths}") String[] documentPaths
    ) {
        this.cachedContext = Arrays.stream(documentPaths)
                .map(Path::of)
                .flatMap(this::listMarkdownFiles)
                .sorted()
                .map(this::readFile)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    // query를 무시하고 전체 문서를 반환 (RAG 전환 시 이 구현체만 교체)
    @Override
    public String retrieve(String query) {
        return cachedContext;
    }

    private Stream<Path> listMarkdownFiles(Path dir) {
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".md"));
        } catch (IOException e) {
            throw new RuntimeException("문서 디렉토리 읽기 실패: " + dir, e);
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("파일 읽기 실패: " + path, e);
        }
    }
}
