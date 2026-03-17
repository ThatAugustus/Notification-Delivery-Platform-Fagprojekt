package com.app.demo.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;

    public SmtpEmailProvider(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendEmail(String from, String to, String subject, String content) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        email.setTo(to);
        email.setSubject(subject);
        email.setText(content);

        mailSender.send(email);
    }
}
