package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Article;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.exception.SummaryNotReadyException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.TtsAudioRepository;
import com.newscurator.repository.VoiceRepository;
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
class TtsServiceTest {

    @Mock private TtsAudioRepository ttsAudioRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private VoiceRepository voiceRepository;
    @Mock private S3AudioUploader s3AudioUploader;
    @Mock private Article mockArticle;

    private TtsService ttsService;

    private static final Long ARTICLE_ID = 1L;
    private static final String VOICE_ID = "Seoyeon";
    private static final String REF_ID = "1";

    @BeforeEach
    void setUp() {
        ttsService = new TtsService(ttsAudioRepository, articleRepository, voiceRepository, s3AudioUploader);

        when(voiceRepository.existsById(VOICE_ID)).thenReturn(true);
        when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(mockArticle));
        when(mockArticle.getSummaryStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(ttsAudioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3AudioUploader.generateUrl(any())).thenAnswer(inv -> "https://cdn.example.com/" + inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────
    // (1) READY 분기: save 호출 없음
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) READY 분기: 기존 READY TtsAudio → 즉시 반환, save 없음")
    void requestArticleTts_ready_returnsImmediatelyWithoutSave() {
        TtsAudio existingReady = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(REF_ID)
                .voiceId(VOICE_ID)
                .build();
        existingReady.complete("tts/article/1/Seoyeon.mp3", null);
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, REF_ID, VOICE_ID))
                .thenReturn(Optional.of(existingReady));

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.status()).isEqualTo(TtsStatus.READY);
        assertThat(response.audioUrl()).isNotNull();
        verify(ttsAudioRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────
    // (2) PENDING/PROCESSING 분기: save 없음
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) PENDING 분기: 기존 PENDING TtsAudio → 기존 반환, save 없음")
    void requestArticleTts_pending_returnsExistingWithoutSave() {
        TtsAudio existingPending = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(REF_ID)
                .voiceId(VOICE_ID)
                .build();
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, REF_ID, VOICE_ID))
                .thenReturn(Optional.of(existingPending));

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.status()).isEqualTo(TtsStatus.PENDING);
        assertThat(response.audioUrl()).isNull(); // audioKey=null → audioUrl=null
        verify(ttsAudioRepository, never()).save(any());
    }

    @Test
    @DisplayName("(2) PROCESSING 분기: 기존 PROCESSING TtsAudio → 기존 반환, save 없음")
    void requestArticleTts_processing_returnsExistingWithoutSave() {
        TtsAudio existingProcessing = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(REF_ID)
                .voiceId(VOICE_ID)
                .build();
        existingProcessing.markProcessing();
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, REF_ID, VOICE_ID))
                .thenReturn(Optional.of(existingProcessing));

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.status()).isEqualTo(TtsStatus.PROCESSING);
        verify(ttsAudioRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────
    // (3) FAILED 분기: 동일 객체 1회 save (신규 INSERT 금지)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) FAILED 분기: resetToPending() 후 동일 행 save, 신규 INSERT 없음")
    void requestArticleTts_failed_resetsToPendingAndSavesSameRow() {
        TtsAudio existingFailed = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(REF_ID)
                .voiceId(VOICE_ID)
                .build();
        existingFailed.fail("이전 오류 메시지");
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, REF_ID, VOICE_ID))
                .thenReturn(Optional.of(existingFailed));

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.status()).isEqualTo(TtsStatus.PENDING);
        assertThat(response.errorMsg()).isNull(); // resetToPending() 후 errorMsg=null
        // 동일 객체에 대해 정확히 1회 save, 새 객체 INSERT 금지
        verify(ttsAudioRepository, times(1)).save(same(existingFailed));
    }

    // ─────────────────────────────────────────────────────────
    // (4) 없음 분기: 신규 PENDING INSERT
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) 없음 분기: 신규 PENDING TtsAudio INSERT")
    void requestArticleTts_notFound_insertsNewPending() {
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, REF_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.status()).isEqualTo(TtsStatus.PENDING);
        // 신규 PENDING 행 1회 save
        verify(ttsAudioRepository, times(1)).save(
                argThat(t -> t.getStatus() == TtsStatus.PENDING && t.getOwnerType() == TtsOwnerType.ARTICLE));
    }

    // ─────────────────────────────────────────────────────────
    // 추가: 예외 케이스
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("summaryStatus != COMPLETED → SummaryNotReadyException (409)")
    void requestArticleTts_summaryNotCompleted_throwsSummaryNotReadyException() {
        when(mockArticle.getSummaryStatus()).thenReturn(ProcessingStatus.PENDING);
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID))
                .isInstanceOf(SummaryNotReadyException.class);
    }

    @Test
    @DisplayName("존재하지 않는 voiceId → IllegalArgumentException (422)")
    void requestArticleTts_invalidVoiceId_throwsIllegalArgument() {
        when(voiceRepository.existsById("no-such-speaker")).thenReturn(false);

        assertThatThrownBy(() -> ttsService.requestArticleTts(ARTICLE_ID, "no-such-speaker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-speaker");
    }

    @Test
    @DisplayName("존재하지 않는 articleId → ArticleNotFoundException (404)")
    void requestArticleTts_articleNotFound_throwsArticleNotFoundException() {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ttsService.requestArticleTts(999L, VOICE_ID))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    @DisplayName("audioKey=null → audioUrl=null (PENDING/PROCESSING 상태 null 가드)")
    void requestArticleTts_audioKeyNull_audioUrlIsNull() {
        when(ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(any(), any(), any()))
                .thenReturn(Optional.empty());

        TtsStatusResponse response = ttsService.requestArticleTts(ARTICLE_ID, VOICE_ID);

        assertThat(response.audioUrl()).isNull();
        verify(s3AudioUploader, never()).generateUrl(any());
    }
}
