package com.newscurator.client.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.config.OAuthConfig;
import com.newscurator.domain.enums.SocialProvider;
import java.util.Base64;
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
public class GoogleOAuthAdapter implements OAuthProviderPort {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthAdapter.class);
    private static final String GOOGLE_AUTH_BASE = "https://accounts.google.com";

    private final RestClient tokenClient;
    private final OAuthConfig.Provider config;
    private final ObjectMapper objectMapper;

    public GoogleOAuthAdapter(OAuthConfig oauthConfig, ObjectMapper objectMapper) {
        this.config = oauthConfig.getGoogle();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);

        String tokenBase = config.getTokenBaseUrl() != null
                ? config.getTokenBaseUrl()
                : "https://oauth2.googleapis.com";
        this.tokenClient = RestClient.builder().baseUrl(tokenBase).requestFactory(factory).build();
    }

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public String getAuthorizeUrl(String state, String redirectUri) {
        return GOOGLE_AUTH_BASE + "/o/oauth2/v2/auth"
                + "?client_id=" + config.getClientId()
                + "&redirect_uri=" + redirectUri
                + "&response_type=code"
                + "&scope=openid+email"
                + "&state=" + state;
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code, String redirectUri) {
        try {
            MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
            tokenParams.add("grant_type", "authorization_code");
            tokenParams.add("client_id", config.getClientId());
            tokenParams.add("client_secret", config.getClientSecret());
            tokenParams.add("redirect_uri", redirectUri);
            tokenParams.add("code", code);

            String tokenJson = tokenClient.post()
                    .uri("/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenParams)
                    .retrieve()
                    .body(String.class);

            JsonNode tokenNode = objectMapper.readTree(tokenJson);
            String idToken = tokenNode.get("id_token").asText();

            // Decode id_token payload (base64url, no signature verification needed — token came from Google TLS)
            String[] parts = idToken.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
            JsonNode payload = objectMapper.readTree(payloadJson);

            String sub = payload.get("sub").asText();
            String email = payload.path("email").asText(null);

            return new OAuthUserInfo(sub, email, null);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Google OAuth exchange failed: {}", e.getMessage());
            throw new RuntimeException("Google OAuth exchange failed", e);
        }
    }

    private static String padBase64(String base64) {
        int pad = 4 - base64.length() % 4;
        if (pad < 4) base64 += "=".repeat(pad);
        return base64;
    }
}
