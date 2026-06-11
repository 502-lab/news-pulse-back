package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oauth")
public class OAuthConfig {

    private Provider kakao = new Provider();
    private Provider google = new Provider();
    private Apple apple = new Apple();

    public Provider getKakao() { return kakao; }
    public void setKakao(Provider kakao) { this.kakao = kakao; }

    public Provider getGoogle() { return google; }
    public void setGoogle(Provider google) { this.google = google; }

    public Apple getApple() { return apple; }
    public void setApple(Apple apple) { this.apple = apple; }

    public static class Provider {
        private String clientId;
        private String clientSecret;
        private String redirectUri;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    }

    public static class Apple extends Provider {
        private String teamId;
        private String keyId;
        private String privateKey;

        public String getTeamId() { return teamId; }
        public void setTeamId(String teamId) { this.teamId = teamId; }

        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    }
}
