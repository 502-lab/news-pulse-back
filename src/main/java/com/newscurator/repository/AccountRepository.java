package com.newscurator.repository;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<Account> findByStatus(AccountStatus status);

    /** 008 US1 — 마지막 ADMIN 가드용 역할별 카운트. */
    long countByRole(AccountRole role);

    /**
     * 008 US1 — 어드민 사용자 목록(이메일 부분검색·역할·상태·가입유형 옵션 필터). null 필터는 무시.
     */
    @Query(
            "SELECT a FROM Account a WHERE "
                    + "(:email IS NULL OR LOWER(a.email) LIKE LOWER(CONCAT('%', :email, '%'))) "
                    + "AND (:role IS NULL OR a.role = :role) "
                    + "AND (:status IS NULL OR a.status = :status) "
                    + "AND (:signupType IS NULL OR a.signupType = :signupType)")
    Page<Account> search(
            @Param("email") String email,
            @Param("role") AccountRole role,
            @Param("status") AccountStatus status,
            @Param("signupType") SignupType signupType,
            Pageable pageable);
}
