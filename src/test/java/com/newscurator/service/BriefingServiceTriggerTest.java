package com.newscurator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
class BriefingServiceTriggerTest {

    @Mock private DailyBriefRepository dailyBriefRepository;
    @Mock private FeedService feedService;
    @Mock private TtsService ttsService;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;
    @Mock private NotificationSendService notificationSendService;

    private BriefingService briefingService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String DEFAULT_VOICE = "Seoyeon";

    @BeforeEach
    void setUp() {
        briefingService = new BriefingService(
                dailyBriefRepository, feedService, ttsService,
                readingPreferenceRepository, notificationSendService, DEFAULT_VOICE, 3);
    }

    private Account buildAccount() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        return account;
    }

    private TtsStatusResponse pendingTts(Long articleId) {
        return new TtsStatusResponse(
                UUID.randomUUID(), TtsOwnerType.ARTICLE, String.valueOf(articleId),
                DEFAULT_VOICE, TtsStatus.PENDING, null, null, null);
    }

    // ─────────────────────────────────────────────────────────
    // (1) 신규 브리핑 생성 시 enqueueBriefing 1회 호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("신규 브리핑 생성 → notificationSendService.enqueueBriefing(accountId) 1회 호출")
    void getOrCreateTodayBrief_newBrief_enqueueBriefingCalledOnce() {
        Account account = buildAccount();

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty());
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.empty());

        Article article = mock(Article.class);
        when(article.getId()).thenReturn(1L);
        when(article.getSummaryStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(feedService.getRankedBriefingCandidates(eq(ACCOUNT_ID), anyInt()))
                .thenReturn(List.of(article, article, article));

        when(dailyBriefRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ttsService.requestArticleTts(anyLong(), any())).thenAnswer(inv -> pendingTts(inv.getArgument(0)));

        briefingService.getOrCreateTodayBrief(account);

        verify(notificationSendService, times(1)).enqueueBriefing(ACCOUNT_ID);
    }

    // ─────────────────────────────────────────────────────────
    // (2) 캐시 히트(기존 브리핑) → enqueueBriefing 미호출
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("캐시 히트(기존 DailyBrief 존재) → enqueueBriefing 0회")
    void getOrCreateTodayBrief_cacheHit_enqueueBriefingNotCalled() {
        Account account = buildAccount();

        DailyBrief existing = mock(DailyBrief.class);
        when(existing.getArticleIds()).thenReturn(new Long[]{10L, 11L, 12L});
        when(existing.getVoiceId()).thenReturn(DEFAULT_VOICE);
        when(existing.getBriefDate()).thenReturn(LocalDate.now());

        when(dailyBriefRepository.findByAccountIdAndBriefDate(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(existing));
        when(ttsService.requestArticleTts(anyLong(), any())).thenAnswer(inv -> pendingTts(inv.getArgument(0)));

        briefingService.getOrCreateTodayBrief(account);

        verify(notificationSendService, never()).enqueueBriefing(any());
    }
}
