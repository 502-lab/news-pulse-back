package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.repository.ArticleEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 009 T010 — ReadTrackingService 단위: 디바운스 위임 + VIEW·SERVER만(forward-seam 미사용). */
@ExtendWith(MockitoExtension.class)
class ReadTrackingServiceTest {

    @Mock private ArticleEventRepository articleEventRepository;
    @InjectMocks private ReadTrackingService service;

    private final UUID account = UUID.randomUUID();

    @Test
    void recordView_inserted_returnsTrue() {
        when(articleEventRepository.insertViewDebounced(eq(account), eq(10L))).thenReturn(1);
        assertThat(service.recordView(account, 10L)).isTrue();
        verify(articleEventRepository).insertViewDebounced(account, 10L);
    }

    @Test
    void recordView_debounced_returnsFalse() {
        when(articleEventRepository.insertViewDebounced(any(), any())).thenReturn(0);
        assertThat(service.recordView(account, 10L)).isFalse();
    }
}
