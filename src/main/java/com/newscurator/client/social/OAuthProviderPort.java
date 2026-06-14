package com.newscurator.client.social;

import com.newscurator.domain.enums.SocialProvider;

public interface OAuthProviderPort {

    SocialProvider getProvider();

    /** OAuth 인가 URL 생성 (state 포함). redirectUri는 화이트리스트 검증 후 전달. */
    String getAuthorizeUrl(String state, String redirectUri);

    /** 인가 코드를 액세스 토큰으로 교환 후 사용자 정보 조회. redirectUri는 authorize와 동일값. */
    OAuthUserInfo exchangeAndFetchUser(String code, String redirectUri);
}
