package com.newscurator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "소셜 신규 가입 완료 요청 (약관 동의 + 계정 생성)")
public record SocialCompleteRequest(
        @Schema(description = "콜백에서 받은 pending-signup 토큰 (TTL 10분)")
        @NotBlank String pendingToken,

        @Schema(description = "약관 동의 목록 (필수 약관 SERVICE·PRIVACY 포함 필수)")
        @NotNull @Size(min = 1) @Valid List<ConsentInput> consents,

        @Schema(description = "만 14세 이상 동의 여부")
        @NotNull Boolean ageConfirmed,

        @Schema(description = "소셜 제공자 추가 사용자 정보 (Apple 최초 로그인 시 name 등). JWT PII 제거로 인해 클라이언트가 재전송. 선택사항.", nullable = true)
        String userInfo
) {}
