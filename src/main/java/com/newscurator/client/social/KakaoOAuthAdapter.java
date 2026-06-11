package com.newscurator.client.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.config.OAuthConfig;
import com.newscurator.domain.enums.SocialProvider;
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
public class KakaoOAuthAdapter implements OAuthProviderPort {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthAdapter.class);
    private static final String AUTH_BASE = "https://kauth.kakao.com";

    private final RestClient tokenClient;
    private final RestClient apiClient;
    private final OAuthConfig.Kakao config;
    private final ObjectMapper objectMapper;

    public KakaoOAuthAdapter(OAuthConfig oauthConfig, ObjectMapper objectMapper) {
        this.config = oauthConfig.getKakao();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);

        String tokenBase = config.getTokenBaseUrl() != null ? config.getTokenBaseUrl() : AUTH_BASE;
        this.tokenClient = RestClient.builder().baseUrl(tokenBase).requestFactory(factory).build();

        String apiBase = config.getApiBaseUrl() != null ? config.getApiBaseUrl() : "https://kapi.kakao.com";
        this.apiClient = RestClient.builder().baseUrl(apiBase).requestFactory(factory).build();
    }

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public String getAuthorizeUrl(String state) {
        return AUTH_BASE + "/oauth/authorize"
                + "?client_id=" + config.getClientId()
                + "&redirect_uri=" + config.getRedirectUri()
                + "&response_type=code"
                + "&state=" + state;
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code) {
        try {
            MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
            tokenParams.add("grant_type", "authorization_code");
            tokenParams.add("client_id", config.getClientId());
            tokenParams.add("client_secret", config.getClientSecret());
            tokenParams.add("redirect_uri", config.getRedirectUri());
            tokenParams.add("code", code);

            String tokenJson = tokenClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenParams)
                    .retrieve()
                    .body(String.class);

            JsonNode tokenNode = objectMapper.readTree(tokenJson);
            String accessToken = tokenNode.get("access_token").asText();

            String userJson = apiClient.get()
                    .uri("/v2/user/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode userNode = objectMapper.readTree(userJson);
            String providerUserId = userNode.get("id").asText();

            String email = null;
            JsonNode kakaoAccount = userNode.path("kakao_account");
            if (!kakaoAccount.isMissingNode() && kakaoAccount.has("email")) {
                email = kakaoAccount.get("email").asText(null);
            }

            return new OAuthUserInfo(providerUserId, email, null);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Kakao OAuth exchange failed: {}", e.getMessage());
            throw new RuntimeException("Kakao OAuth exchange failed", e);
        }
    }
}
