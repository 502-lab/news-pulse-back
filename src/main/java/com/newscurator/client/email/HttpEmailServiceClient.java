package com.newscurator.client.email;

import com.newscurator.exception.EmailDeliveryException;
import java.util.List;
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
    private final String fromAddress;

    public HttpEmailServiceClient(
            @Value("${email-service.base-url}") String baseUrl,
            @Value("${email-service.api-key}") String apiKey,
            @Value("${email-service.from-address}") String fromAddress) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
                .build();
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        send(toEmail,
                "이메일 인증 코드",
                "<p>안녕하세요!</p>"
                + "<p>이메일 인증 코드: <strong>" + code + "</strong></p>"
                + "<p>이 코드는 15분간 유효합니다.</p>");
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        send(toEmail,
                "비밀번호 재설정 코드",
                "<p>비밀번호 재설정 코드: <strong>" + code + "</strong></p>"
                + "<p>이 코드는 15분간 유효하며, 5회 오입력 시 자동 무효화됩니다.</p>"
                + "<p>본인이 요청하지 않은 경우 이 이메일을 무시하세요.</p>");
    }

    @Override
    public void sendSocialOnlyNotice(String toEmail) {
        send(toEmail,
                "소셜 로그인 계정 안내",
                "<p>해당 이메일은 소셜 로그인으로 가입된 계정입니다.</p>"
                + "<p>기존에 사용하신 소셜 계정(카카오/구글/애플)으로 로그인해 주세요.</p>");
    }

    private void send(String toEmail, String subject, String html) {
        Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(toEmail),
                "subject", subject,
                "html", html
        );
        try {
            restClient.post()
                    .uri("/emails")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Resend delivery failed to={}: {}", toEmail, e.getMessage());
            throw new EmailDeliveryException("Email delivery failed: " + e.getMessage());
        }
    }
}
