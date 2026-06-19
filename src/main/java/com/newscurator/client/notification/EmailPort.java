package com.newscurator.client.notification;

public interface EmailPort {

    void send(String to, String subject, String htmlBody);
}
