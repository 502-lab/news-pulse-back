package com.newscurator.client.keyword;

import java.util.Set;

/**
 * 키워드 추출 포트. 텍스트에서 한국어 명사(NNG/NNP) 키워드를 추출한다(불용어 제거, 중복 제거).
 *
 * <p>MVP 구현은 {@link NoriKeywordExtractor}(Lucene Nori). 향후 다른 분석기/사전으로 교체 가능
 * (004 TtsProvider / 005 PushNotificationPort 격리 패턴).
 */
public interface KeywordExtractor {

    /** 텍스트에서 명사 키워드 Set을 반환한다. 빈/추출 0건은 빈 Set. */
    Set<String> extractNouns(String text);
}
