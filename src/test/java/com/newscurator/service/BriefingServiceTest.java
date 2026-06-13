package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Account;
import com.newscurator.domain.Article;
import com.newscurator.domain.DailyBrief;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.BriefingResponse;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.NoFeedArticlesException;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingServiceTest {

    @Mock private DailyBriefRepository dailyBriefRepository;
    @Mock private FeedService feedService;
    @Mock private TtsService ttsService;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;

    private BriefingService briefingService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String DEFAULT_VOICE = "harin";

    @BeforeEach
    void setUp() {
        briefingService = new BriefingService(
                dailyBriefRepository, feedService, ttsService,
                readingPreferenceRepository, DEFAULT_VOICE, 3);
    }

    private Account buildAccount() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        return account;
    }

    private Article buildArticle(Long id, ProcessingStatus summaryStatus) {
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(id);
        when(article.getSummaryStatus()).thenReturn(summaryStatus);
        return article;
    }

    private TtsStatusResponse pendingTts(Long articleId) {
        return new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, String.valueOf(articleId),
                DEFAULT_VOICE, TtsStatus.PENDING, null, null, null);
    }

    private TtsStatusResponse readyTts(Long articleId) {
        return new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, String.valueOf(articleId),
                DEFAULT_VOICE, TtsStatus.READY, "https://cdn.example.com/tts/" + articleId, null, null);
    }

    // ─────────────────────────────────────────────────────────
    // (1) 캐시 히트: 기존 DailyBrief 존재 → 새 저장 없음, TTS 현재 상태 재조회
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(1) 당일 브리핑 캐시 히트: DailyBrief save 없음, feedService 미호출, TTS 재조회 3회")
    void getOrCreateTodayBrief_cacheHit_doesNotSaveNewBrief() {
        Account account = buildAccount();

        DailyBrief existing = mock(DailyBrief.class);
        when(existing.getBriefDate()).thenReturn(LocalDate.now());
        when(existing.getArticleIds()).thenReturn(new Long[]{10L, 11L, 12L});
        when(existing.getVoiceId()).thenReturn(DEFAULT_VOICE);

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(existing));
        when(ttsService.requestArticleTts(anyLong(), eq(DEFAULT_VOICE)))
                .thenAnswer(inv -> readyTts(inv.getArgument(0)));

        BriefingResponse response = briefingService.getOrCreateTodayBrief(account);

        // 새 DailyBrief 저장 없음
        verify(dailyBriefRepository, never()).save(any());
        // 피드 조회 없음
        verify(feedService, never()).getRankedBriefingCandidates(any(), anyInt());
        // 기존 3건 TTS 현재 상태 재조회
        verify(ttsService, times(3)).requestArticleTts(anyLong(), eq(DEFAULT_VOICE));
        assertThat(response.articleIds()).containsExactly(10L, 11L, 12L);
        assertThat(response.ttsItems()).hasSize(3);
        assertThat(response.voiceId()).isEqualTo(DEFAULT_VOICE);
    }

    // ─────────────────────────────────────────────────────────
    // (2) 신규 생성: DailyBrief 저장 + TtsService N번 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(2) 신규 브리핑: DailyBrief 저장 1회, TtsService 3번 호출, articleIds 일치")
    void getOrCreateTodayBrief_newBrief_savesAndReturnsTtsItems() {
        Account account = buildAccount();

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty());
        when(readingPreferenceRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        List<Article> candidates = List.of(
                buildArticle(1L, ProcessingStatus.COMPLETED),
                buildArticle(2L, ProcessingStatus.COMPLETED),
                buildArticle(3L, ProcessingStatus.COMPLETED));
        when(feedService.getRankedBriefingCandidates(eq(ACCOUNT_ID), anyInt()))
                .thenReturn(candidates);
        when(ttsService.requestArticleTts(anyLong(), eq(DEFAULT_VOICE)))
                .thenAnswer(inv -> pendingTts(inv.getArgument(0)));

        BriefingResponse response = briefingService.getOrCreateTodayBrief(account);

        // DailyBrief 저장 1회, articleIds 검증
        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(dailyBriefRepository).save(captor.capture());
        assertThat(Arrays.asList(captor.getValue().getArticleIds()))
                .containsExactly(1L, 2L, 3L);

        // TtsService 3번 호출
        verify(ttsService, times(3)).requestArticleTts(anyLong(), eq(DEFAULT_VOICE));
        assertThat(response.articleIds()).containsExactly(1L, 2L, 3L);
        assertThat(response.ttsItems()).hasSize(3);
        assertThat(response.voiceId()).isEqualTo(DEFAULT_VOICE);
    }

    // ─────────────────────────────────────────────────────────
    // (3) COMPLETED/비-COMPLETED 혼합 → COMPLETED만 큐에 포함
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(3) 피드 후보 [A-COMPL, B-PEND, C-COMPL, D-FAIL, E-COMPL] N=3 → A·C·E만 포함, B·D 제외")
    void getOrCreateTodayBrief_mixedStatus_onlyCompletedArticlesInQueue() {
        Account account = buildAccount();

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty());
        when(readingPreferenceRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        Article a = buildArticle(100L, ProcessingStatus.COMPLETED);
        Article b = buildArticle(200L, ProcessingStatus.PENDING);
        Article c = buildArticle(300L, ProcessingStatus.COMPLETED);
        Article d = buildArticle(400L, ProcessingStatus.FAILED);
        Article e = buildArticle(500L, ProcessingStatus.COMPLETED);
        when(feedService.getRankedBriefingCandidates(eq(ACCOUNT_ID), anyInt()))
                .thenReturn(List.of(a, b, c, d, e));
        when(ttsService.requestArticleTts(anyLong(), eq(DEFAULT_VOICE)))
                .thenAnswer(inv -> pendingTts(inv.getArgument(0)));

        BriefingResponse response = briefingService.getOrCreateTodayBrief(account);

        // 응답 articleIds: A·C·E만 포함, B·D 제외
        assertThat(response.articleIds())
                .containsExactly(100L, 300L, 500L)
                .doesNotContain(200L, 400L);
        assertThat(response.ttsItems()).hasSize(3);

        // DailyBrief.articleIds에도 A·C·E만
        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(dailyBriefRepository).save(captor.capture());
        assertThat(Arrays.asList(captor.getValue().getArticleIds()))
                .containsExactly(100L, 300L, 500L)
                .doesNotContain(200L, 400L);
    }

    // ─────────────────────────────────────────────────────────
    // (4) COMPLETED 0건 → NoFeedArticlesException
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(4) COMPLETED 0건 → NoFeedArticlesException, save 없음, TTS 미호출")
    void getOrCreateTodayBrief_noCompletedArticles_throwsNoFeedArticlesException() {
        Account account = buildAccount();

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty());
        when(readingPreferenceRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.empty());
        Article noSummary1 = buildArticle(1L, ProcessingStatus.PENDING);
        Article noSummary2 = buildArticle(2L, ProcessingStatus.FAILED);
        when(feedService.getRankedBriefingCandidates(eq(ACCOUNT_ID), anyInt()))
                .thenReturn(List.of(noSummary1, noSummary2));

        assertThatThrownBy(() -> briefingService.getOrCreateTodayBrief(account))
                .isInstanceOf(NoFeedArticlesException.class);
        verify(dailyBriefRepository, never()).save(any());
        verify(ttsService, never()).requestArticleTts(anyLong(), anyString());
    }
}
