package com.newscurator.repository;

import com.newscurator.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /**
     * FCM 토큰 upsert — token UNIQUE 제약 기반.
     * 동일 token 재등록 시 account_id·platform·updated_at 갱신, row 수 불변.
     */
    @Modifying
    @Query(
            value = """
            INSERT INTO device_tokens (account_id, token, platform, created_at, updated_at)
            VALUES (CAST(:accountId AS uuid), :token, :platform, now(), now())
            ON CONFLICT (token)
            DO UPDATE SET account_id = CAST(:accountId AS uuid),
                          platform   = :platform,
                          updated_at = now()
            """,
            nativeQuery = true)
    void upsert(
            @Param("accountId") String accountId,
            @Param("token") String token,
            @Param("platform") String platform);

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByAccountId(UUID accountId);

    /** max-5 eviction용: 가장 오래된 토큰부터 반환 */
    List<DeviceToken> findTop6ByAccountIdOrderByCreatedAtAsc(UUID accountId);

    long countByAccountId(UUID accountId);

    Optional<DeviceToken> findByIdAndAccountId(Long id, UUID accountId);

    /** FCM UNREGISTERED 응답 시 토큰 삭제 */
    void deleteByToken(String token);
}
