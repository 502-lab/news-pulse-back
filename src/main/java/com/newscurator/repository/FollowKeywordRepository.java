package com.newscurator.repository;

import com.newscurator.domain.FollowKeyword;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowKeywordRepository extends JpaRepository<FollowKeyword, UUID> {
    List<FollowKeyword> findByAccountId(UUID accountId);

    @Modifying
    @Query("DELETE FROM FollowKeyword fk WHERE fk.account.id = :accountId")
    void deleteByAccountId(@Param("accountId") UUID accountId);
}
