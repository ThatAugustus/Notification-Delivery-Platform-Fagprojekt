package com.app.demo.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.email.EmailProvider;
import com.app.demo.model.Notification;
import com.app.demo.repository.DeliveryAttemptRepository;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class EmailWorker extends BaseNotificationWorker {

    private final EmailProvider emailProvider;

    public EmailWorker(
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RetryPolicy retryPolicy,
            EmailProvider emailProvider,
            MeterRegistry meterRegistry) {
        
        // Pass shared dependencies up to the parent
        super(objectMapper, notificationRepository, deliveryAttemptRepository, retryPolicy, meterRegistry);
        
        // Keep Email-specific dependencies here
        this.emailProvider = emailProvider;
    }

    // Called by the dynamic listener registrar for each tenant queue
    public void listen(Message message) {
        super.processMessage(message);
    }

    @Override
    protected void deliver(NotificationPayload payload, Notification notification) throws Exception {
        String from = payload.getSenderEmail() != null ? payload.getSenderEmail() : "noreply@notificationplatform.com"; // TODO: use real default email, with domain we own.
        String to = payload.getRecipient();
        String subject = payload.getSubject() != null ? payload.getSubject() : "No Subject";
        String content = payload.getContent();

        emailProvider.sendEmail(from, to, subject, content);
    }
}
