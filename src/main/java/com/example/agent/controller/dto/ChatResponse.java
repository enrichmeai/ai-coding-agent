package com.example.agent.controller.dto;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;

import java.util.List;

public record ChatResponse(
        String sessionId,
        String title,
        List<ChatMessage> newMessages,
        List<ChatMessage> history,
        TokenUsage usage
) {}
