package com.app.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NotificationRequest {
    @NotNull private String channel;
    @NotBlank private String recipient;
    @NotBlank private String content;
    private String subject;        // optional
    @NotBlank private String idempotencyKey;
}