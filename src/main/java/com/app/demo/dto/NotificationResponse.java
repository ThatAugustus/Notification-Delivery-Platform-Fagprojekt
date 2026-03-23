package com.app.demo.dto;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;


//What the API returns when you GET a notification.
// We excluded all tje request fields that the tenant doesn't need to see, like the message body. So they only see the necessary information about the notification

@Data
@AllArgsConstructor
public class NotificationResponse {
    private UUID id;
    private String channel;
    private String recipient;
    private String subject;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}