package com.example.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String sessionId,       // optional; if null, a new session will be created
        @NotBlank String message
) {}
