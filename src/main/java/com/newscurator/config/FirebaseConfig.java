package com.newscurator.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public Optional<FirebaseApp> firebaseApp(
            @Value("${firebase.service-account-json:}") String base64Json) {

        if (base64Json == null || base64Json.isBlank()) {
            log.info("[Firebase] service-account-json 미설정 — FCM 비활성화");
            return Optional.empty();
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Json.trim());
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            log.info("[Firebase] FirebaseApp 초기화 완료");
            return Optional.of(app);
        } catch (IOException e) {
            log.error("[Firebase] FirebaseApp 초기화 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
