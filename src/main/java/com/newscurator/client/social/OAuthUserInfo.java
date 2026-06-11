package com.newscurator.client.social;

/**
 * @param providerUserId provider의 고유 사용자 식별자 (Kakao: id, Google/Apple: sub)
 * @param email          provider에서 받은 이메일 (Kakao 미동의 시 null)
 * @param rawUserInfo    Apple 최초 로그인 시 userInfo JSON, 나머지는 null
 */
public record OAuthUserInfo(
        String providerUserId,
        String email,
        String rawUserInfo
) {}
