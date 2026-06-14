package com.app.demo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data // Lombok annotation that generates getters, setters, toString, equals, and hashCode
public class NotificationRequest {
    //NotBlank is used to validate the input together with @Valid in the controller
    @NotBlank private String channel;
    @NotBlank private String recipient;
    @NotBlank private String content;
    private String subject;        // optional
    @NotBlank private String idempotencyKey;
    private String webhookUrl;

}