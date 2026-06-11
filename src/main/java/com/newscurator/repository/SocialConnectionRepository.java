package com.newscurator.repository;

import com.newscurator.domain.SocialConnection;
import com.newscurator.domain.enums.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialConnectionRepository extends JpaRepository<SocialConnection, UUID> {

    Optional<SocialConnection> findByProviderAndProviderUserId(SocialProvider provider,
                                                                String providerUserId);

    boolean existsByAccountId(UUID accountId);
}
