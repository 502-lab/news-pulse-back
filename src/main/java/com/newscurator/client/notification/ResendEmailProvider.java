package com.newscurator.client.notification;

import com.newscurator.client.email.EmailServiceClient;
import org.springframework.stereotype.Component;

@Component
public class ResendEmailProvider implements EmailPort {

    private final EmailServiceClient emailServiceClient;

    public ResendEmailProvider(EmailServiceClient emailServiceClient) {
        this.emailServiceClient = emailServiceClient;
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        emailServiceClient.sendHtml(to, subject, htmlBody);
    }
}
