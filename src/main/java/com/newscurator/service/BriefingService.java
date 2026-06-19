package com.newscurator.service;

import com.newscurator.domain.Account;
import com.newscurator.domain.Article;
import com.newscurator.domain.DailyBrief;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.response.BriefingResponse;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.NoFeedArticlesException;
import com.newscurator.repository.DailyBriefRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    private final DailyBriefRepository dailyBriefRepository;
    private final FeedService feedService;
    private final TtsService ttsService;
    private final ReadingPreferenceRepository readingPreferenceRepository;
    private final String defaultVoiceId;
    private final int briefingArticleCount;

    private final NotificationSendService notificationSendService;

    public BriefingService(
            DailyBriefRepository dailyBriefRepository,
            FeedService feedService,
            TtsService ttsService,
            ReadingPreferenceRepository readingPreferenceRepository,
            NotificationSendService notificationSendService,
            @Value("${app.tts.default-voice-id}") String defaultVoiceId,
            @Value("${app.tts.briefing.article-count:5}") int briefingArticleCount) {
        this.dailyBriefRepository = dailyBriefRepository;
        this.feedService = feedService;
        this.ttsService = ttsService;
        this.readingPreferenceRepository = readingPreferenceRepository;
        this.notificationSendService = notificationSendService;
        this.defaultVoiceId = defaultVoiceId;
        this.briefingArticleCount = briefingArticleCount;
    }

    /**
     * 당일 브리핑 캐시 히트: 저장된 article_ids로 현재 TTS 상태를 재조회하여 반환(stale 금지).
     * 캐시 미스: 개인화 피드 상위 후보에서 summaryStatus=COMPLETED 기사 N건 선정 후 신규 생성.
     * COMPLETED 0건이면 NoFeedArticlesException → 404.
     */
    @Transactional
    public BriefingResponse getOrCreateTodayBrief(Account account) {
        LocalDate today = LocalDate.now();
        Optional<DailyBrief> existing =
                dailyBriefRepository.findByAccountIdAndBriefDate(account.getId(), today);

        if (existing.isPresent()) {
            log.debug("브리핑 캐시 히트: accountId={}, date={}", account.getId(), today);
            return buildResponse(existing.get());
        }

        String voiceId = readingPreferenceRepository.findByAccountId(account.getId())
                .map(ReadingPreference::getVoiceId)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultVoiceId);

        // summaryStatus 필터 전 충분한 후보 확보를 위해 3배 요청
        List<Article> rankedCandidates =
                feedService.getRankedBriefingCandidates(account.getId(), briefingArticleCount * 3);

        List<Long> articleIds = rankedCandidates.stream()
                .filter(a -> a.getSummaryStatus() == ProcessingStatus.COMPLETED)
                .limit(briefingArticleCount)
                .map(Article::getId)
                .toList();

        if (articleIds.isEmpty()) {
            throw new NoFeedArticlesException("오늘 브리핑에 사용할 완료된 요약 기사가 없습니다.");
        }

        DailyBrief brief = DailyBrief.builder()
                .account(account)
                .briefDate(today)
                .articleIds(articleIds.toArray(Long[]::new))
                .voiceId(voiceId)
                .build();
        dailyBriefRepository.save(brief);
        log.debug("브리핑 신규 생성: accountId={}, articles={}, voice={}",
                account.getId(), articleIds.size(), voiceId);

        try {
            notificationSendService.enqueueBriefing(account.getId());
        } catch (Exception e) {
            log.warn("[NOTIFICATION] enqueueBriefing 실패 (브리핑 생성은 성공): accountId={}, msg={}",
                    account.getId(), e.getMessage());
        }

        List<TtsStatusResponse> ttsItems = articleIds.stream()
                .map(id -> ttsService.requestArticleTts(id, voiceId))
                .toList();

        return new BriefingResponse(today, articleIds, voiceId, ttsItems);
    }

    private BriefingResponse buildResponse(DailyBrief brief) {
        List<Long> articleIds = Arrays.asList(brief.getArticleIds());
        String voiceId = brief.getVoiceId();
        // 캐시 히트: requestArticleTts 재호출로 현재 상태 반영 (PENDING→READY 진행 반영)
        List<TtsStatusResponse> ttsItems = articleIds.stream()
                .map(id -> ttsService.requestArticleTts(id, voiceId))
                .toList();
        return new BriefingResponse(brief.getBriefDate(), articleIds, voiceId, ttsItems);
    }
}
