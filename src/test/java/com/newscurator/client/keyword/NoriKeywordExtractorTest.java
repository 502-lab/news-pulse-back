package com.newscurator.client.keyword;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T015 + 품질 튜닝 크라운주얼: Nori 명사 추출 단위 테스트 (컨테이너 불요).
 *
 * <p>DecompoundMode.NONE(복합명사 통째 유지) + UserDictionary(OOV 교정) + stopwords 보강 검증.
 * 진단(NoriExtractionDiagnostic)에서 깨졌던 케이스가 교정됐는지 term-level로 단언한다.
 */
class NoriKeywordExtractorTest {

    private final NoriKeywordExtractor extractor = new NoriKeywordExtractor();

    @Test
    @DisplayName("명사(NNG/NNP) 추출 + 조사·일반어 제거")
    void extractsNouns_removesParticles() {
        Set<String> terms = extractor.extractNouns("정부가 금리를 인상했다");

        assertThat(terms).contains("정부", "금리", "인상");
        assertThat(terms).doesNotContain("가", "를", "했다");
    }

    @Test
    @DisplayName("복합명사(삼성전자)는 DecompoundMode.NONE으로 통째 유지 — 삼성/전자 조각 미포함")
    void compoundNoun_keptWhole() {
        // NONE 전환 전(DISCARD)에는 [삼성, 전자]로 쪼개졌으나, NONE은 삼성전자를 통째로 유지한다.
        Set<String> terms = extractor.extractNouns("삼성전자 실적 호조");

        assertThat(terms).contains("삼성전자", "실적", "호조");
        assertThat(terms).doesNotContain("삼성", "전자");
    }

    @Test
    @DisplayName("불용어 제거 — '오늘','기자' + boilerplate('발표') 제외")
    void removesStopwords() {
        // '발표'는 boilerplate로 stopwords-ko.txt에 보강됨 → 결과에서 제외(기대값 수정 사유).
        Set<String> terms = extractor.extractNouns("오늘 기자 회견에서 경제 정책 발표");

        assertThat(terms).doesNotContain("오늘", "기자", "발표");
        assertThat(terms).contains("회견", "경제", "정책");
    }

    @Test
    @DisplayName("1자 명사 제외(2자 이상)")
    void excludesSingleChar() {
        Set<String> terms = extractor.extractNouns("물 값 상승");
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

    // ── 품질 튜닝 크라운주얼: 진단에서 깨졌던 케이스 교정 검증 ──────────────

    @Test
    @DisplayName("대한민국 → 통째 유지, '대한'/'민국' 조각 미포함")
    void daehanminguk_notSplit() {
        Set<String> terms = extractor.extractNouns("대한민국 경제 성장");
        assertThat(terms).contains("대한민국");
        assertThat(terms).doesNotContain("대한", "민국");
    }

    @Test
    @DisplayName("월드컵 → 통째 유지, '월드' 단독 미포함")
    void worldcup_notSplit() {
        Set<String> terms = extractor.extractNouns("월드컵 예선 경기");
        assertThat(terms).contains("월드컵");
        assertThat(terms).doesNotContain("월드");
    }

    @Test
    @DisplayName("백혈병 → 통째 유지('백혈' 조각 아님)")
    void leukemia_notSplit() {
        Set<String> terms = extractor.extractNouns("백혈병 치료제 임상");
        assertThat(terms).contains("백혈병");
        assertThat(terms).doesNotContain("백혈");
    }

    @Test
    @DisplayName("연준 → userDict 경유 통째 인식, '연주'(의미붕괴) 미포함")
    void fed_userDict_notMissegmented() {
        Set<String> terms = extractor.extractNouns("미국 연준 금리 인상");
        assertThat(terms).contains("연준");
        assertThat(terms).doesNotContain("연주");
    }

    @Test
    @DisplayName("홍명보 → userDict 경유 인명 유지, '보호'(오분절) 미포함")
    void coachName_userDict_notMissegmented() {
        Set<String> terms = extractor.extractNouns("홍명보 감독 경질");
        assertThat(terms).contains("홍명보");
        assertThat(terms).doesNotContain("보호");
    }

    @Test
    @DisplayName("boilerplate stopwords(공개·발표·속보) 결과에서 제외")
    void boilerplateStopwords_excluded() {
        Set<String> terms = extractor.extractNouns("경제 정책 공개 발표 속보");
        assertThat(terms).contains("경제", "정책");
        assertThat(terms).doesNotContain("공개", "발표", "속보");
    }
}
