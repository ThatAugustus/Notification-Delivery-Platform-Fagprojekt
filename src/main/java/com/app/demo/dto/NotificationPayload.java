package com.app.demo.dto;

import java.util.UUID;

import com.app.demo.model.enums.NotificationChannel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPayload {
    private UUID notificationId;
    private NotificationChannel channel;
    private String senderEmail;
    private String recipient;
    private String subject;
    private String content;
    private String webhookUrl;
    private String webhookSecret;
}