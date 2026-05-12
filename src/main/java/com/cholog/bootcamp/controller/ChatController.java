package com.cholog.bootcamp.controller;

import com.cholog.bootcamp.dto.ChatBotRequest;
import com.cholog.bootcamp.dto.ChatBotResponse;
import com.cholog.bootcamp.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public ChatBotResponse chat(@RequestBody ChatBotRequest request) {
        return chatService.sendMessage(request);
    }
}
