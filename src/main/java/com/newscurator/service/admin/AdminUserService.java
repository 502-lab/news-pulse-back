package com.newscurator.service.admin;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.dto.response.AdminUserDetailResponse;
import com.newscurator.dto.response.AdminUserSummaryResponse;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.exception.LastAdminProtectedException;
import com.newscurator.exception.SelfMutationForbiddenException;
import com.newscurator.repository.AccountRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 사용자 관리(008 US1). 목록·상세, 역할 변경, 활성/비활성.
 *
 * <p>자기보호 가드(FR-014): (a) 자기 자신 강등/비활성 금지, (b) 마지막 ADMIN 강등/비활성 금지.
 * 모든 변형 액션은 같은 트랜잭션에서 {@link AdminAuditService#record}로 감사 기록(diff 포함).
 */
@Service
public class AdminUserService {

    private static final String ACTION_ROLE_CHANGE = "ROLE_CHANGE";
    private static final String ACTION_STATUS_CHANGE = "ACCOUNT_STATUS_CHANGE";

    private final AccountRepository accountRepository;
    private final AdminAuditService auditService;

    public AdminUserService(AccountRepository accountRepository, AdminAuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /** 사용자 목록(이메일 부분검색·역할·상태·가입유형 옵션 필터, 페이지네이션). */
    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> list(
            String email,
            AccountRole role,
            AccountStatus status,
            SignupType signupType,
            Pageable pageable) {
        String emailFilter = (email == null || email.isBlank()) ? null : email;
        return accountRepository
                .search(
                        emailFilter,
                        role == null ? null : role.name(),
                        status == null ? null : status.name(),
                        signupType == null ? null : signupType.name(),
                        pageable)
                .map(AdminUserSummaryResponse::from);
    }

    /** 사용자 상세 + UserStats. */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getDetail(UUID accountId) {
        return AdminUserDetailResponse.from(load(accountId));
    }

    /**
     * 역할 변경(USER↔ADMIN). 가드: 자기 자신 강등 금지, 마지막 ADMIN 강등 금지.
     * "강등" = ADMIN→USER. USER→ADMIN(승격)은 가드 비대상.
     */
    @Transactional
    public void changeRole(UUID actorId, UUID targetId, AccountRole newRole) {
        Account target = load(targetId);
        AccountRole before = target.getRole();
        if (before == newRole) {
            return; // 변경 없음 — no-op(감사도 남기지 않음)
        }
        boolean isDemotion = before == AccountRole.ADMIN && newRole == AccountRole.USER;
        if (isDemotion) {
            guardSelf(actorId, targetId, "자기 자신의 역할을 강등할 수 없습니다");
            guardLastAdmin(target, "마지막 관리자의 역할을 강등할 수 없습니다");
        }
        target.changeRole(newRole);
        accountRepository.save(target);
        auditService.record(
                actorId,
                ACTION_ROLE_CHANGE,
                AuditTargetType.ACCOUNT,
                targetId.toString(),
                Map.of("before", before.name(), "after", newRole.name()));
    }

    /** 활성/비활성. 가드: 자기 자신 비활성 금지, 마지막 ADMIN 비활성 금지. */
    @Transactional
    public void changeStatus(UUID actorId, UUID targetId, boolean active) {
        Account target = load(targetId);
        AccountStatus before = target.getStatus();
        AccountStatus after = active ? AccountStatus.ACTIVE : AccountStatus.SUSPENDED;
        if (before == after) {
            return; // 변경 없음
        }
        if (!active) { // 비활성화만 가드
            guardSelf(actorId, targetId, "자기 자신을 비활성화할 수 없습니다");
            guardLastAdmin(target, "마지막 관리자를 비활성화할 수 없습니다");
            target.deactivate();
        } else {
            target.activate();
        }
        accountRepository.save(target);
        auditService.record(
                actorId,
                ACTION_STATUS_CHANGE,
                AuditTargetType.ACCOUNT,
                targetId.toString(),
                Map.of("before", before.name(), "after", after.name()));
    }

    // ── 가드 ──

    private void guardSelf(UUID actorId, UUID targetId, String message) {
        if (actorId.equals(targetId)) {
            throw new SelfMutationForbiddenException(message);
        }
    }

    /** target이 ADMIN이고 시스템에 ADMIN이 그 하나뿐이면 거부. */
    private void guardLastAdmin(Account target, String message) {
        if (target.getRole() == AccountRole.ADMIN
                && accountRepository.countByRole(AccountRole.ADMIN) <= 1) {
            throw new LastAdminProtectedException(message);
        }
    }

    private Account load(UUID accountId) {
        return accountRepository
                .findById(accountId)
                .orElseThrow(() -> new AdminTargetNotFoundException("사용자를 찾을 수 없습니다: " + accountId));
    }
}
