package com.newscurator.repository;

import com.newscurator.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByFamilyIdAndConsumedAtIsNullAndIsRevokedFalse(UUID familyId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = TRUE WHERE r.familyId = :familyId")
    void revokeByFamilyId(@Param("familyId") UUID familyId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = TRUE WHERE r.account.id = :accountId")
    void revokeByAccountId(@Param("accountId") UUID accountId);

    // Counts families where a blast (reuse-attack revocation) occurred within the window.
    // Uses blastedAt — set only on the blast path, not on normal logout — to avoid
    // false-escalation from logout or from tokens consumed before the blast happened.
    @Query("SELECT COUNT(DISTINCT r.familyId) FROM RefreshToken r " +
           "WHERE r.account.id = :accountId " +
           "AND r.blastedAt IS NOT NULL " +
           "AND r.blastedAt > :since")
    long countRecentFamilyBlastsByAccountId(@Param("accountId") UUID accountId,
                                            @Param("since") Instant since);
}
