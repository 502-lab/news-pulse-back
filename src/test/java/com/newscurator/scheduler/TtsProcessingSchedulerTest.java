package com.newscurator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.client.ai.TtsProvider;
import com.newscurator.domain.Summary;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.exception.AiProviderException;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.NotificationSendService;
import com.newscurator.service.S3AudioUploader;
import java.util.List;
import java.util.Optional;
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
class TtsProcessingSchedulerTest {

    @Mock private TtsAudioClaimer claimer;
    @Mock private TtsProvider ttsProvider;
    @Mock private S3AudioUploader s3AudioUploader;
    @Mock private SummaryRepository summaryRepository;
    @Mock private NotificationSendService notificationSendService;
    @Mock private DailyBriefRepository dailyBriefRepository;
    @Mock private Summary mockSummary;

    private TtsProcessingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TtsProcessingScheduler(claimer, ttsProvider, s3AudioUploader, summaryRepository,
                notificationSendService, dailyBriefRepository, 10);
        // 기본: 요약 조회 → 빈 결과 (resolveTtsText가 IllegalStateException → FAILED)
        when(summaryRepository.findByArticleIdAndDepth(any(), eq(SummaryDepth.BALANCED)))
                .thenReturn(Optional.empty());
        when(summaryRepository.findByArticleIdAndDepth(any(), eq(SummaryDepth.BRIEF)))
                .thenReturn(Optional.empty());

        // 완료 요약 mock 기본 설정
        when(mockSummary.getStatus()).thenReturn(SummarySlotStatus.COMPLETED);
        when(mockSummary.getContent()).thenReturn("테스트 기사 요약 내용입니다.");
    }

    private TtsAudio buildProcessingTtsAudio(String refId, String voiceId) {
        TtsAudio tts = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(refId)
                .voiceId(voiceId)
                .build();
        tts.markProcessing(); // claimer가 claimBatch에서 수행한 상태 시뮬레이션
        return tts;
    }

    // ─────────────────────────────────────────────────────────
    // (1) 정상 처리: PROCESSING → 완료 요약 조회 → Naver → S3 → READY
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 정상 처리: Naver·S3 성공 → TtsAudio status=READY, audioKey != null")
    void process_success_ttsAudioBecomesReady() {
        TtsAudio tts = buildProcessingTtsAudio("1", "Seoyeon");
        assertThat(tts.getStatus()).isEqualTo(TtsStatus.PROCESSING); // claimer 완료 후 상태 확인

        // 완료 요약 반환 — resolveTtsText가 orElseThrow를 통과하도록
        when(summaryRepository.findByArticleIdAndDepth(eq(1L), eq(SummaryDepth.BALANCED)))
                .thenReturn(Optional.of(mockSummary));

        when(claimer.claimBatch(10)).thenReturn(List.of(tts));
        when(ttsProvider.generate(eq("Seoyeon"), any()))
                .thenReturn(new byte[]{1, 2, 3});
        when(s3AudioUploader.upload(any(), eq("tts/article/1/Seoyeon.mp3")))
                .thenReturn("tts/article/1/Seoyeon.mp3");

        scheduler.process();

        // Naver TTS 1회 호출
        verify(ttsProvider).generate(eq("Seoyeon"), any());
        // S3 업로드 1회 호출
        verify(s3AudioUploader).upload(any(), eq("tts/article/1/Seoyeon.mp3"));
        // 결과 저장 호출
        verify(claimer).persistResult(tts);
        // 도메인 객체 상태: READY, audioKey 설정
        assertThat(tts.getStatus()).isEqualTo(TtsStatus.READY);
        assertThat(tts.getAudioKey()).isEqualTo("tts/article/1/Seoyeon.mp3");
    }

    // ─────────────────────────────────────────────────────────
    // (2) Naver API 실패: FAILED 저장, S3 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) Naver API 실패: TtsAudio status=FAILED, errorMsg != null, S3 미호출")
    void process_naverFails_ttsAudioBecomesFailed() {
        TtsAudio tts = buildProcessingTtsAudio("2", "Seoyeon");

        // 완료 요약 반환 — Naver 실패를 제대로 테스트하기 위해 summary는 정상 반환
        when(summaryRepository.findByArticleIdAndDepth(eq(2L), eq(SummaryDepth.BALANCED)))
                .thenReturn(Optional.of(mockSummary));

        when(claimer.claimBatch(10)).thenReturn(List.of(tts));
        when(ttsProvider.generate(any(), any()))
                .thenThrow(new AiProviderException("Naver error: HTTP 503"));

        scheduler.process();

        // S3 업로드 미호출
        verify(s3AudioUploader, never()).upload(any(), any());
        // 결과 저장은 호출됨 (FAILED 상태로)
        verify(claimer).persistResult(tts);
        // 도메인 객체 상태: FAILED, errorMsg 설정
        assertThat(tts.getStatus()).isEqualTo(TtsStatus.FAILED);
        assertThat(tts.getErrorMsg()).isNotNull();
        assertThat(tts.getAudioKey()).isNull();
    }

    @Test
    @DisplayName("빈 배치 → Naver·S3 미호출")
    void process_emptyBatch_noExternalCalls() {
        when(claimer.claimBatch(10)).thenReturn(List.of());

        scheduler.process();

        verify(ttsProvider, never()).generate(any(), any());
        verify(s3AudioUploader, never()).upload(any(), any());
        verify(claimer, never()).persistResult(any());
    }
}
