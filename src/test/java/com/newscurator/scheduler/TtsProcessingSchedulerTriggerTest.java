package com.newscurator.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.client.ai.TtsProvider;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.NotificationSendService;
import com.newscurator.service.S3AudioUploader;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TtsProcessingSchedulerTriggerTest {

    @Mock private TtsAudioClaimer ttsAudioClaimer;
    @Mock private TtsProvider ttsProvider;
    @Mock private S3AudioUploader s3AudioUploader;
    @Mock private SummaryRepository summaryRepository;
    @Mock private NotificationSendService notificationSendService;
    @Mock private DailyBriefRepository dailyBriefRepository;
    @Mock private com.newscurator.service.admin.SchedulerControlService schedulerControl;

    private TtsProcessingScheduler scheduler;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new TtsProcessingScheduler(
                ttsAudioClaimer, ttsProvider, s3AudioUploader, summaryRepository,
                notificationSendService, dailyBriefRepository, 10, schedulerControl);
        when(schedulerControl.isEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    private TtsAudio buildTtsAudio(String refId, String voiceId) {
        TtsAudio tts = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(refId)
                .voiceId(voiceId)
                .build();
        return tts;
    }

    private void stubSummary(Long articleId) {
        com.newscurator.domain.Summary summary = mock(com.newscurator.domain.Summary.class);
        when(summary.getStatus()).thenReturn(SummarySlotStatus.COMPLETED);
        when(summary.getContent()).thenReturn("Summary text for " + articleId);
        when(summaryRepository.findByArticleIdAndDepth(
                eq(articleId), eq(com.newscurator.domain.enums.SummaryDepth.BALANCED)))
                .thenReturn(java.util.Optional.of(summary));
    }

    // ─────────────────────────────────────────────────────────
    // (1) tts.complete() 후 DailyBrief 보유 계정 → enqueueTtsReady 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTS complete → DailyBrief 계정 존재 → enqueueTtsReady(accountId, refId) 호출")
    void process_ttsCompleted_enqueuesNotification() throws Exception {
        String refId = "42";
        String voiceId = "Seoyeon";
        TtsAudio tts = buildTtsAudio(refId, voiceId);
        stubSummary(42L);

        when(ttsAudioClaimer.claimBatch(10)).thenReturn(List.of(tts));
        when(ttsProvider.generate(eq(voiceId), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(dailyBriefRepository.findAccountIdsByArticleIdAndVoiceId(42L, voiceId))
                .thenReturn(List.of(ACCOUNT_ID));

        scheduler.process();

        verify(notificationSendService, times(1)).enqueueTtsReady(ACCOUNT_ID, refId);
    }

    // ─────────────────────────────────────────────────────────
    // (2) tts.complete() 후 DailyBrief 없음 → enqueueTtsReady 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTS complete → DailyBrief 계정 없음 → enqueueTtsReady 0회")
    void process_ttsCompleted_noDailyBriefAccounts_noEnqueue() throws Exception {
        String refId = "43";
        String voiceId = "Seoyeon";
        TtsAudio tts = buildTtsAudio(refId, voiceId);
        stubSummary(43L);

        when(ttsAudioClaimer.claimBatch(10)).thenReturn(List.of(tts));
        when(ttsProvider.generate(eq(voiceId), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(dailyBriefRepository.findAccountIdsByArticleIdAndVoiceId(43L, voiceId))
                .thenReturn(List.of());

        scheduler.process();

        verify(notificationSendService, never()).enqueueTtsReady(any(), anyString());
    }

    // ─────────────────────────────────────────────────────────
    // (3) TTS 실패(fail) → enqueueTtsReady 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTS fail → enqueueTtsReady 0회")
    void process_ttsFailed_noEnqueue() throws Exception {
        String refId = "44";
        String voiceId = "Seoyeon";
        TtsAudio tts = buildTtsAudio(refId, voiceId);

        // BALANCED 요약 없음 → IllegalStateException → tts.fail()
        when(summaryRepository.findByArticleIdAndDepth(eq(44L), any()))
                .thenReturn(java.util.Optional.empty());

        when(ttsAudioClaimer.claimBatch(10)).thenReturn(List.of(tts));

        scheduler.process();

        verify(notificationSendService, never()).enqueueTtsReady(any(), anyString());
    }
}
