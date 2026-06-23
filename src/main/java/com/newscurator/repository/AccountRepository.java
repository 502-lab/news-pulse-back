package com.newscurator.repository;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
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

    /** 008 US2 — KPI 활성 사용자 수. */
    long countByStatus(AccountStatus status);

    /**
     * 008 US1 — 어드민 사용자 목록(이메일 부분검색·역할·상태·가입유형 옵션 필터). null 필터는 무시.
     *
     * <p>nullable enum/문자열 파라미터는 JPQL에서 Postgres 바인드 타입 추론 실패("could not determine data type
     * of parameter")를 유발하므로 native + CAST(:p AS text)로 처리한다. role·status·signup_type 컬럼은
     * EnumType.STRING 저장이라 문자열 비교가 정합. role/status/signupType은 enum.name() 또는 null로 전달.
     */
    @Query(
            value =
                    "SELECT * FROM accounts a WHERE "
                            + "(CAST(:email AS text) IS NULL"
                            + " OR LOWER(a.email) LIKE LOWER(CONCAT('%', CAST(:email AS text), '%'))) "
                            + "AND (CAST(:role AS text) IS NULL OR a.role = CAST(:role AS text)) "
                            + "AND (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text)) "
                            + "AND (CAST(:signupType AS text) IS NULL OR a.signup_type = CAST(:signupType AS text))",
            countQuery =
                    "SELECT count(*) FROM accounts a WHERE "
                            + "(CAST(:email AS text) IS NULL"
                            + " OR LOWER(a.email) LIKE LOWER(CONCAT('%', CAST(:email AS text), '%'))) "
                            + "AND (CAST(:role AS text) IS NULL OR a.role = CAST(:role AS text)) "
                            + "AND (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text)) "
                            + "AND (CAST(:signupType AS text) IS NULL OR a.signup_type = CAST(:signupType AS text))",
            nativeQuery = true)
    Page<Account> search(
            @Param("email") String email,
            @Param("role") String role,
            @Param("status") String status,
            @Param("signupType") String signupType,
            Pageable pageable);
}
