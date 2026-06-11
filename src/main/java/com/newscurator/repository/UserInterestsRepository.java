package com.newscurator.repository;

import com.newscurator.domain.UserInterests;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserInterestsRepository extends JpaRepository<UserInterests, UUID> {
    List<UserInterests> findByAccountId(UUID accountId);

    @Modifying
    @Query("DELETE FROM UserInterests ui WHERE ui.account.id = :accountId")
    void deleteByAccountId(@Param("accountId") UUID accountId);
}
