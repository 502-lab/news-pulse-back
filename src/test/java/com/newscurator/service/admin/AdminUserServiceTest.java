package com.newscurator.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Account;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.AccountRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * T023 AdminUserService 단위 테스트 — 목록 필터·역할 변경·상태 변경(가드 분기·감사 호출).
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AdminAuditService auditService;
    @InjectMocks private AdminUserService service;

    private Account account(UUID id, AccountRole role) {
        Account a =
                Account.builder()
                        .email("u@test.com")
                        .role(role)
                        .status(AccountStatus.ACTIVE)
                        .signupType(SignupType.EMAIL)
                        .build();
        // id는 빌더에 없으므로 리플렉션 대신 findById 스텁으로 주입
        return a;
    }

    @Test
    void list_appliesFiltersAndMaps() {
        Account a = account(UUID.randomUUID(), AccountRole.USER);
        when(accountRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(a)));

        Page<?> page =
                service.list("u", AccountRole.USER, AccountStatus.ACTIVE, SignupType.EMAIL,
                        PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        verify(accountRepository).search(eq("u"), eq(AccountRole.USER), eq(AccountStatus.ACTIVE),
                eq(SignupType.EMAIL), any());
    }

    @Test
    void changeRole_promotion_savesAndAudits() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Account t = account(target, AccountRole.USER);
        when(accountRepository.findById(target)).thenReturn(Optional.of(t));

        service.changeRole(actor, target, AccountRole.ADMIN);

        assertThat(t.getRole()).isEqualTo(AccountRole.ADMIN);
        verify(accountRepository).save(t);
        verify(auditService)
                .record(eq(actor), eq("ROLE_CHANGE"), eq(AuditTargetType.ACCOUNT),
                        eq(target.toString()), eq(Map.of("before", "USER", "after", "ADMIN")));
    }

    @Test
    void changeRole_noop_whenSameRole_noAudit() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(accountRepository.findById(target))
                .thenReturn(Optional.of(account(target, AccountRole.USER)));

        service.changeRole(actor, target, AccountRole.USER); // 동일 → no-op

        verify(accountRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void changeStatus_deactivate_savesAndAudits() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Account t = account(target, AccountRole.USER);
        when(accountRepository.findById(target)).thenReturn(Optional.of(t));

        service.changeStatus(actor, target, false);

        assertThat(t.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(auditService, times(1))
                .record(eq(actor), eq("ACCOUNT_STATUS_CHANGE"), eq(AuditTargetType.ACCOUNT),
                        eq(target.toString()), any());
    }

    @Test
    void getDetail_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getDetail(id))
                .isInstanceOf(AdminTargetNotFoundException.class);
    }
}
