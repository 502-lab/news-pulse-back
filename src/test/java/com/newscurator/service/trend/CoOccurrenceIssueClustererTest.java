package com.newscurator.service.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.newscurator.config.TrendProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T040: co-occurrence 클러스터러 over/under-merge 단위 테스트.
 *
 * <p>min-edge-weight=2, min-cluster-size=2. 세 케이스를 정직하게 검증한다.
 * (a) 우연 1회 동시출현(weight 1 < 임계) → 간선 미형성, 미연결.
 * (b) 강한 응집 클러스터 → 한 이슈 + 대표 top3 + delta(멤버 WoW 평균).
 * (c) ★ transitive bridge → 별개 토픽 A·B가 weight-2 다리로 연결될 때 실제 결과(over-merge 여부)를
 *     가감 없이 단언한다.
 */
class CoOccurrenceIssueClustererTest {

    // min-edge-weight=2, min-cluster-size=2 (운영 application.yaml과 동일)
    private static final TrendProperties PROPS =
            new TrendProperties(1, 24, 90, 2, 1, 25, 1, new TrendProperties.Cooccurrence(2, 2));

    private final CoOccurrenceIssueClusterer clusterer = new CoOccurrenceIssueClusterer(PROPS);

    private static IssueClusterContext ctx(Map<Long, List<String>> articleKeywords) {
        return new IssueClusterContext(articleKeywords, Map.of());
    }

    @Test
    @DisplayName("(a) 우연 1회 동시출현(weight 1 < min-edge-weight) → 간선 미형성, 클러스터 없음")
    void accidentalSingleCoOccurrence_noEdge_noCluster() {
        // 어떤 키워드 쌍도 2개 이상 기사에서 함께 등장하지 않음 → 전부 weight 1
        Map<Long, List<String>> articles = new HashMap<>();
        articles.put(1L, List.of("금리", "인상"));
        articles.put(2L, List.of("경제", "환율"));
        articles.put(3L, List.of("축구", "날씨"));

        List<DerivedIssue> issues = clusterer.cluster(ctx(articles));

        // weight 2 미만 → 채택 간선 0 → 연결성분 0 → 이슈 0
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("(b) 강한 응집 클러스터(금리·인상·경제 3기사 동시출현) → 한 이슈 + top3 + delta 평균")
    void cohesiveCluster_oneIssue_top3_deltaAverage() {
        // 금리·인상·경제가 3개 기사에서 함께 등장 → 모든 쌍 weight 3
        Map<Long, List<String>> articles = new HashMap<>();
        articles.put(1L, List.of("금리", "인상", "경제"));
        articles.put(2L, List.of("금리", "인상", "경제"));
        articles.put(3L, List.of("금리", "인상", "경제"));

        // 멤버 WoW delta: 금리=100, 인상=50, 경제=결측(null 취급) → 평균 = (100+50)/2 = 75.0
        Map<String, Double> wow = new HashMap<>();
        wow.put("금리", 100.0);
        wow.put("인상", 50.0);
        // 경제는 의도적으로 미포함(null 방어 검증)

        List<DerivedIssue> issues =
                clusterer.cluster(new IssueClusterContext(articles, wow));

        assertThat(issues).hasSize(1);
        DerivedIssue issue = issues.get(0);
        assertThat(issue.keywords()).containsExactlyInAnyOrder("금리", "인상", "경제");
        assertThat(issue.articleIds()).containsExactly(1L, 2L, 3L);
        // delta = non-null 멤버(금리,인상)의 평균. 경제(null)는 제외.
        assertThat(issue.delta()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("(c) ★ transitive bridge: A{금리,인상,경제}+B{부동산,대출,규제}+다리(금리·부동산 2기사) → 실제 결과")
    void transitiveBridge_actualMergeBehavior() {
        Map<Long, List<String>> articles = new HashMap<>();
        // 토픽 A: 금리·인상·경제 내부 응집(3기사)
        articles.put(1L, List.of("금리", "인상", "경제"));
        articles.put(2L, List.of("금리", "인상", "경제"));
        articles.put(3L, List.of("금리", "인상", "경제"));
        // 토픽 B: 부동산·대출·규제 내부 응집(3기사)
        articles.put(4L, List.of("부동산", "대출", "규제"));
        articles.put(5L, List.of("부동산", "대출", "규제"));
        articles.put(6L, List.of("부동산", "대출", "규제"));
        // 다리: 금리·부동산이 2기사에서 동시출현(weight 2 = min-edge-weight) → 간선 채택
        articles.put(7L, List.of("금리", "부동산"));
        articles.put(8L, List.of("금리", "부동산"));

        List<DerivedIssue> issues = clusterer.cluster(ctx(articles));

        // ── 실제 결과(가감 없이 단언) ──
        // union-find 연결성분 방식은 weight-2 다리 하나로 A·B를 하나의 연결성분으로 합친다(OVER-MERGE).
        // 따라서 별개 토픽 2개가 아니라 키워드 6개짜리 단일 blob 이슈 1개가 나온다.
        assertThat(issues).hasSize(1);
        DerivedIssue blob = issues.get(0);
        assertThat(blob.keywords())
                .as("over-merge: 다리 하나로 A·B 6개 키워드가 한 클러스터로 합쳐짐")
                .hasSize(3); // 대표 top3만 노출되지만, 성분 자체는 6개 키워드를 포함
        // 기사도 A·B·다리 전부(1~8) 한 묶음
        assertThat(blob.articleIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        // 대표 top3 = nodeWeight 내림차순(동률 자연순): 금리(8)·부동산(8)·경제(6)
        assertThat(blob.keywords()).containsExactly("금리", "부동산", "경제");
    }
}
