package com.app.demo.email;

public interface EmailProvider {
    /**
     * Sends an email.
     * 
     * @param from    The sender email address (e.g. billing@notificationplatform.com)
     * @param to      The recipient email address
     * @param subject The email subject
     * @param content The email body (HTML or plain text)
     * @throws Exception if the email fails to send
     */
    void sendEmail(String from, String to, String subject, String content) throws Exception;
}
