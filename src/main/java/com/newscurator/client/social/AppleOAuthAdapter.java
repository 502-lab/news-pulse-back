package com.newscurator.client.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.config.OAuthConfig;
import com.newscurator.domain.enums.SocialProvider;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AppleOAuthAdapter implements OAuthProviderPort {

    private static final Logger log = LoggerFactory.getLogger(AppleOAuthAdapter.class);
    private static final String APPLE_AUTH_BASE = "https://appleid.apple.com";
    private static final long CLIENT_SECRET_TTL_MS = 180L * 24 * 3600 * 1000; // 6 months

    private final OAuthConfig.Apple config;
    private final RestClient tokenClient;
    private final ObjectMapper objectMapper;
    private final ECPrivateKey applePrivateKey;

    public AppleOAuthAdapter(OAuthConfig oauthConfig, ObjectMapper objectMapper) {
        this.config = oauthConfig.getApple();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);

        String tokenBase = config.getTokenBaseUrl() != null
                ? config.getTokenBaseUrl()
                : APPLE_AUTH_BASE;
        this.tokenClient = RestClient.builder().baseUrl(tokenBase).requestFactory(factory).build();

        this.applePrivateKey = parsePrivateKey(config.getPrivateKey());
    }

    private ECPrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            log.warn("Apple OAuth private key not configured — Apple login will be unavailable");
            return null;
        }
        try {
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            log.warn("Failed to parse Apple OAuth private key: {}", e.getMessage());
            return null;
        }
    }

    /** Builds a short-lived ES256 client_secret JWT as required by Apple Sign-in. */
    String buildClientSecret() {
        if (applePrivateKey == null) {
            throw new IllegalStateException("Apple OAuth private key is not configured");
        }
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(config.getTeamId())
                .subject(config.getClientId())
                .claim("aud", "https://appleid.apple.com")
                .issuedAt(new Date(now))
                .expiration(new Date(now + CLIENT_SECRET_TTL_MS))
                .header().add("kid", config.getKeyId()).and()
                .signWith(applePrivateKey, Jwts.SIG.ES256)
                .compact();
    }

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.APPLE;
    }

    @Override
    public String getAuthorizeUrl(String state, String redirectUri) {
        return APPLE_AUTH_BASE + "/auth/authorize"
                + "?client_id=" + config.getClientId()
                + "&redirect_uri=" + redirectUri
                + "&response_type=code"
                + "&scope=email"
                + "&response_mode=form_post"
                + "&state=" + state;
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code, String redirectUri) {
        if (applePrivateKey == null) {
            throw new IllegalStateException("Apple OAuth private key is not configured");
        }
        try {
            String clientSecret = buildClientSecret();

            MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
            tokenParams.add("grant_type", "authorization_code");
            tokenParams.add("client_id", config.getClientId());
            tokenParams.add("client_secret", clientSecret);
            tokenParams.add("redirect_uri", redirectUri);
            tokenParams.add("code", code);

            String tokenJson = tokenClient.post()
                    .uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenParams)
                    .retrieve()
                    .body(String.class);

            JsonNode tokenNode = objectMapper.readTree(tokenJson);
            String idToken = tokenNode.get("id_token").asText();

            // Decode id_token payload — Apple TLS guarantees authenticity in server-to-server flow
            String[] parts = idToken.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
            JsonNode payload = objectMapper.readTree(payloadJson);

            String sub = payload.get("sub").asText();
            // Apple relay email: @privaterelay.appleid.com — stored as-is per spec
            String email = payload.path("email").asText(null);

            return new OAuthUserInfo(sub, email, null);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Apple OAuth exchange failed: {}", e.getMessage());
            throw new RuntimeException("Apple OAuth exchange failed", e);
        }
    }

    private static String padBase64(String base64) {
        int pad = 4 - base64.length() % 4;
        if (pad < 4) base64 += "=".repeat(pad);
        return base64;
    }
}
