package com.app.demo.worker;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.model.DeliveryAttempt;
import com.app.demo.model.Notification;
import com.app.demo.model.enums.DeliveryAttemptStatus;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.DeliveryAttemptRepository;
import com.app.demo.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);

    private final ObjectMapper objectMapper;
    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final JavaMailSender mailSender;

    public EmailWorker(
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            JavaMailSender mailSender) {
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.mailSender = mailSender;
    }

    @RabbitListener(queues = "email-queue")
    public void processEmail(Message message) {
        long startTime = System.currentTimeMillis();
        NotificationPayload payload = null;
        Notification notification = null;

        try {
            // 1. Parse JSON from RabbitMQ
            String body = new String(message.getBody());
            payload = objectMapper.readValue(body, NotificationPayload.class);
            log.info("Worker received email request for notification: {}", payload.getNotificationId());

            // 2. Fetch Notification + Mark PROCESSING
            final String notifId = payload.getNotificationId().toString();
            notification = notificationRepository.findById(payload.getNotificationId())
                    .orElseThrow(() -> new IllegalArgumentException("Notification with id: " + notifId + " not found."));
            
            notification.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(notification);

            // 3. Send the Email via Mailpit
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setTo(payload.getRecipient());
            helper.setSubject(payload.getSubject() != null ? payload.getSubject() : "No Subject");
            helper.setText(payload.getContent(), true); // true = HTML
            helper.setFrom(payload.getSenderEmail() != null ? payload.getSenderEmail() : "noreply@notificationplatform.com");

            mailSender.send(mimeMessage);

            // 4. Success -> Record it
            long duration = System.currentTimeMillis() - startTime;
            notification.setStatus(NotificationStatus.DELIVERED);
            notificationRepository.save(notification);

            DeliveryAttempt attempt = new DeliveryAttempt();
            attempt.setNotification(notification);
            attempt.setAttemptNumber(notification.getRetryCount() + 1);
            attempt.setStatus(DeliveryAttemptStatus.SUCCESS);
            attempt.setErrorMessage(null);
            attempt.setDurationMs(duration);

            deliveryAttemptRepository.save(attempt);

            log.info("√ Email delivered successfully in {}ms to {}", duration, payload.getRecipient());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("X Failed to send email", e);

            // 5. if Failure -> Record it
            if (notification != null) {
                notification.setStatus(NotificationStatus.FAILED);
                notification.setRetryCount(notification.getRetryCount() + 1);
                notificationRepository.save(notification);

                DeliveryAttempt attempt = new DeliveryAttempt();
                attempt.setNotification(notification);
                attempt.setAttemptNumber(notification.getRetryCount());
                attempt.setStatus(DeliveryAttemptStatus.FAILED);
                attempt.setErrorMessage(e.getMessage());
                attempt.setDurationMs(duration);

                deliveryAttemptRepository.save(attempt);
            }
        }
    }
}
