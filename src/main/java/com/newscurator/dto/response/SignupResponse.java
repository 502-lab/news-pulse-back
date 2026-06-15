package com.newscurator.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 응답 — 이메일 인증 완료 전 임시 토큰만 반환")
public record SignupResponse(
        @Schema(description = "이메일 인증 전용 임시 토큰 (15분 유효, /auth/email-verification/** 에서만 사용 가능)")
        String pendingToken,
        @Schema(description = "인증 이메일 발송 성공 여부 (false여도 /email-verification/request 재요청 가능)")
        boolean verificationEmailSent
) {}
