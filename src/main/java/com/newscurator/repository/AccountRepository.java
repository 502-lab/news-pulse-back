package com.newscurator.repository;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    List<Account> findByStatus(AccountStatus status);
}
