package com.newscurator.client.email;

import com.newscurator.exception.EmailDeliveryException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpEmailServiceClient implements EmailServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmailServiceClient.class);

    private final RestClient restClient;

    public HttpEmailServiceClient(
            @Value("${email-service.base-url}") String baseUrl,
            @Value("${email-service.api-key}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-API-Key", apiKey)
                .requestFactory(factory)
                .build();
    }

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        post("/send-verification-code", Map.of("toEmail", toEmail, "code", code));
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        post("/send-password-reset-code", Map.of("toEmail", toEmail, "code", code));
    }

    @Override
    public void sendSocialOnlyNotice(String toEmail) {
        post("/send-social-only-notice", Map.of("toEmail", toEmail));
    }

    private void post(String path, Map<String, String> body) {
        try {
            restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Email delivery failed for path={}: {}", path, e.getMessage());
            throw new EmailDeliveryException("Email delivery failed: " + e.getMessage());
        }
    }
}
