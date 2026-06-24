package com.newscurator.service;

import com.newscurator.config.FeedRankingProperties;
import com.newscurator.domain.Article;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 기사 관련성 점수 계산 — 카테고리 매칭 + 키워드 매칭(상한) + 최근성. 003 피드 랭킹과 010 놓친 기사 추천이
 * 공유하는 단일 출처(behavior-preserving 추출, 복제 금지).
 *
 * <p>본 계산은 {@code FeedService.computeScore}에서 <b>본문·인자·연산순서·반환을 100% 보존</b>해 이동한 것이다.
 * 003 피드 랭킹 단언(FeedServiceTest)이 추출 후 불변으로 통과하는 것이 behavior-preserving 증거다.
 */
@Component
public class ArticleRelevanceScorer {

    private final FeedRankingProperties rankingProps;

    public ArticleRelevanceScorer(FeedRankingProperties rankingProps) {
        this.rankingProps = rankingProps;
    }

    /** 카테고리 매칭 + 키워드 매칭(5회 상한) + 최근성 ratio 점수. (구 FeedService.computeScore 동일) */
    public double score(
            Article article,
            List<String> userCategories,
            List<String> userKeywords,
            Instant referenceTs) {
        double score = 0;

        if (article.getCategory() != null
                && userCategories.contains(article.getCategory().name())) {
            score += rankingProps.categoryMatchScore();
        }

        long matches =
                userKeywords.stream()
                        .filter(kw -> article.getTitle().toLowerCase().contains(kw.toLowerCase()))
                        .count();
        score +=
                Math.min(
                        matches * rankingProps.keywordMatchScore(),
                        5L * rankingProps.keywordMatchScore());

        long hoursOld = Duration.between(article.getPublishedAt().toInstant(), referenceTs).toHours();
        if (hoursOld >= 0 && hoursOld <= rankingProps.recencyWindowHours()) {
            double ratio = 1.0 - (double) hoursOld / rankingProps.recencyWindowHours();
            score += rankingProps.recencyMaxScore() * ratio;
        }

        return score;
    }
}
