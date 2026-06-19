package com.newscurator.service;

import com.newscurator.domain.DeviceToken;
import com.newscurator.domain.enums.DevicePlatform;
import com.newscurator.dto.response.DeviceTokenResponse;
import com.newscurator.exception.ResourceNotFoundException;
import com.newscurator.repository.DeviceTokenRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {

    private static final int MAX_TOKENS_PER_ACCOUNT = 5;

    private final DeviceTokenRepository deviceTokenRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    /**
     * FCM 토큰 upsert 등록.
     * 동일 token이 이미 존재하면 account_id·platform·updated_at 갱신 (row 추가 없음).
     * 계정당 토큰 5개 초과 시 가장 오래된(created_at ASC) 토큰 삭제.
     */
    @Transactional
    public DeviceTokenResponse register(UUID accountId, String token, DevicePlatform platform) {
        deviceTokenRepository.upsert(accountId.toString(), token, platform.name());
        evictOldestIfOverLimit(accountId);

        DeviceToken saved = deviceTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceToken", token));
        return DeviceTokenResponse.from(saved);
    }

    /**
     * 토큰 삭제 (소유권 검증 포함).
     */
    @Transactional
    public void delete(UUID accountId, Long tokenId) {
        DeviceToken token = deviceTokenRepository.findByIdAndAccountId(tokenId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceToken", tokenId));
        deviceTokenRepository.delete(token);
    }

    /**
     * FCM UNREGISTERED 응답 수신 시 해당 토큰 삭제.
     */
    @Transactional
    public void deleteByToken(String token) {
        deviceTokenRepository.deleteByToken(token);
    }

    private void evictOldestIfOverLimit(UUID accountId) {
        List<DeviceToken> tokens = deviceTokenRepository.findTop6ByAccountIdOrderByCreatedAtAsc(accountId);
        if (tokens.size() > MAX_TOKENS_PER_ACCOUNT) {
            deviceTokenRepository.delete(tokens.get(0));
            deviceTokenRepository.flush();
        }
    }
}
