package com.newscurator.client.social;

import com.newscurator.domain.enums.SocialProvider;

public interface OAuthProviderPort {

    SocialProvider getProvider();

    /** OAuth 인가 URL 생성 (state 포함). */
    String getAuthorizeUrl(String state);

    /** 인가 코드를 액세스 토큰으로 교환 후 사용자 정보 조회. */
    OAuthUserInfo exchangeAndFetchUser(String code);
}
