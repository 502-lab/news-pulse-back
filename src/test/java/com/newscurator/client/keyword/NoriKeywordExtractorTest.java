package com.newscurator.client.keyword;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T015: Nori 명사 추출 단위 테스트 (컨테이너 불요). Java 25 런타임 동작 검증 포함. */
class NoriKeywordExtractorTest {

    private final NoriKeywordExtractor extractor = new NoriKeywordExtractor();

    @Test
    @DisplayName("명사(NNG/NNP) 추출 + 조사·일반어 제거")
    void extractsNouns_removesParticles() {
        Set<String> terms = extractor.extractNouns("정부가 금리를 인상했다");

        // 명사: 정부, 금리, 인상 / 조사(가,를), 동사어미는 제거
        assertThat(terms).contains("정부", "금리", "인상");
        assertThat(terms).doesNotContain("가", "를", "했다");
    }

    @Test
    @DisplayName("고유명사(NNP) 추출")
    void extractsProperNoun() {
        Set<String> terms = extractor.extractNouns("삼성전자 실적 발표");

        assertThat(terms).contains("삼성", "전자", "실적", "발표")
                .anySatisfy(t -> assertThat(t).isNotBlank());
    }

    @Test
    @DisplayName("불용어(stopwords-ko.txt) 제거 — '오늘','기자' 등")
    void removesStopwords() {
        Set<String> terms = extractor.extractNouns("오늘 기자 회견에서 경제 정책 발표");

        assertThat(terms).doesNotContain("오늘", "기자");
        assertThat(terms).contains("회견", "경제", "정책", "발표");
    }

    @Test
    @DisplayName("1자 명사 제외(2자 이상)")
    void excludesSingleChar() {
        Set<String> terms = extractor.extractNouns("물 값 상승");
        // '물','값'(1자) 제외, '상승' 포함
        assertThat(terms).contains("상승");
        assertThat(terms).allSatisfy(t -> assertThat(t.length()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("중복 제거 — 같은 term 1회만")
    void deduplicates() {
        Set<String> terms = extractor.extractNouns("금리 인상 금리 동결 금리 인하");
        assertThat(terms).containsOnlyOnce("금리");
    }

    @Test
    @DisplayName("null/blank → 빈 Set, 예외 없음")
    void blankReturnsEmpty() {
        assertThat(extractor.extractNouns(null)).isEmpty();
        assertThat(extractor.extractNouns("   ")).isEmpty();
        assertThat(extractor.extractNouns("")).isEmpty();
    }
}
