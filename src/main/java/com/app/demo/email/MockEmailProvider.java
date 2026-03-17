package com.app.demo.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "mock")
public class MockEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(MockEmailProvider.class);

    // Keep track of sent emails in memory so tests can verify them
    private final List<MockEmail> sentEmails = new ArrayList<>();

    @Override
    public void sendEmail(String from, String to, String subject, String content) {
        log.info("\n=== MOCK EMAIL SENT ===" +
                 "\nFrom:    {}" +
                 "\nTo:      {}" +
                 "\nSubject: {}" +
                 "\nContent: \n{}\n=======================\n", 
                 from, to, subject, content);
                 
        sentEmails.add(new MockEmail(from, to, subject, content));
    }

    public List<MockEmail> getSentEmails() {
        return Collections.unmodifiableList(sentEmails);
    }

    public void clear() {
        sentEmails.clear();
    }

    // A simple record to hold the email data for testing assertion
    public record MockEmail(String from, String to, String subject, String content) {}
}
