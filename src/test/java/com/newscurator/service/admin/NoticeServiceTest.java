package com.newscurator.service.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.newscurator.repository.NoticeRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T061 NoticeService 단위 — 입력 검증(title ≤200·content NOT NULL). */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    @Mock private NoticeRepository noticeRepository;
    @Mock private AdminAuditService auditService;
    @InjectMocks private NoticeService service;

    private final UUID actor = UUID.randomUUID();

    @Test
    void create_blankTitle_rejected() {
        assertThatThrownBy(() -> service.create(actor, "  ", "본문", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void create_tooLongTitle_rejected() {
        String longTitle = "x".repeat(201);
        assertThatThrownBy(() -> service.create(actor, longTitle, "본문", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_blankContent_rejected() {
        assertThatThrownBy(() -> service.create(actor, "제목", "  ", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }
}
