package com.app.demo.worker;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
            EmailProvider emailProvider) {
        
        // Pass shared dependencies up to the parent
        super(objectMapper, notificationRepository, deliveryAttemptRepository, retryPolicy);
        
        // Keep Email-specific dependencies here
        this.emailProvider = emailProvider;
    }

    @RabbitListener(queues = "email-queue")
    public void listen(Message message) {
        // we let the parent class do the hard work of parsing JSON and updating databases
        super.processMessage(message);
    }

    @Override
    protected void deliver(NotificationPayload payload, Notification notification) throws Exception {
        // THIS is the only thing the EmailWorker actually has to do!
        String from = payload.getSenderEmail() != null ? payload.getSenderEmail() : "noreply@notificationplatform.com";
        String to = payload.getRecipient();
        String subject = payload.getSubject() != null ? payload.getSubject() : "No Subject";
        String content = payload.getContent();

        emailProvider.sendEmail(from, to, subject, content);
    }
}
