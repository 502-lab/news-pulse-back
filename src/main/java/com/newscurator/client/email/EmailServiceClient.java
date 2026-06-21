package com.newscurator.client.email;

public interface EmailServiceClient {
    void sendVerificationCode(String toEmail, String code);
    void sendPasswordResetCode(String toEmail, String code);
    void sendSocialOnlyNotice(String toEmail);
    void sendHtml(String toEmail, String subject, String html);
}
