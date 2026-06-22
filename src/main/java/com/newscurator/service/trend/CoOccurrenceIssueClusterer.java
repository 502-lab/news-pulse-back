package com.newscurator.service.trend;

import com.newscurator.config.TrendProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 키워드 동시출현(co-occurrence) 기반 이슈 클러스터러 (MVP, FR-010).
 *
 * <p>알고리즘:
 * <ol>
 *   <li>기사별 키워드 쌍의 동시출현 빈도(edge weight = 함께 등장한 기사 수)를 센다.</li>
 *   <li>edge weight ≥ {@code minEdgeWeight}인 쌍만 그래프 간선으로 채택(우연 1회 동시출현 차단, over-merge 방지).</li>
 *   <li>union-find로 연결성분을 구한다.</li>
 *   <li>키워드 수 ≥ {@code minClusterSize}인 성분만 이슈로 승격.</li>
 *   <li>대표 키워드 = 성분 내 동시출현 가중치 상위 3. article_ids = 성분 키워드를 포함한 기사. delta = 멤버 WoW delta 평균.</li>
 * </ol>
 *
 * <p><b>알려진 한계</b>: 연결성분 방식은 transitive bridge에 취약하다 — 별개 토픽 A·B가 단일 강한 다리
 * (예: 두 토픽 키워드가 ≥minEdgeWeight 기사에서 동시출현)로 연결되면 하나의 blob으로 합쳐진다(over-merge).
 * 강화가 필요하면 임계 상향 / cohesion 기반 분할 / community detection(예: Louvain)으로 v2에서 교체.
 */
@Component
public class CoOccurrenceIssueClusterer implements IssueClusterer {

    private final TrendProperties trendProperties;

    public CoOccurrenceIssueClusterer(TrendProperties trendProperties) {
        this.trendProperties = trendProperties;
    }

    @Override
    public List<DerivedIssue> cluster(IssueClusterContext ctx) {
        int minEdge = trendProperties.cooccurrence().minEdgeWeight();
        int minCluster = trendProperties.cooccurrence().minClusterSize();

        // 1) 기사별 키워드 쌍 동시출현 빈도
        Map<Pair, Integer> pairWeight = new HashMap<>();
        for (List<String> kws : ctx.articleKeywords().values()) {
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(kws));
            for (int i = 0; i < distinct.size(); i++) {
                for (int j = i + 1; j < distinct.size(); j++) {
                    pairWeight.merge(Pair.of(distinct.get(i), distinct.get(j)), 1, Integer::sum);
                }
            }
        }

        // 2) 임계 이상 간선만 채택 → union-find
        UnionFind uf = new UnionFind();
        // 노드별 가중치(대표 키워드 선정용): 채택된 간선 가중치 합
        Map<String, Integer> nodeWeight = new HashMap<>();
        for (Map.Entry<Pair, Integer> e : pairWeight.entrySet()) {
            if (e.getValue() >= minEdge) {
                Pair p = e.getKey();
                uf.union(p.a(), p.b());
                nodeWeight.merge(p.a(), e.getValue(), Integer::sum);
                nodeWeight.merge(p.b(), e.getValue(), Integer::sum);
            }
        }

        // 3) 연결성분별 키워드 집합
        Map<String, Set<String>> components = new HashMap<>();
        for (String term : nodeWeight.keySet()) {
            components.computeIfAbsent(uf.find(term), k -> new TreeSet<>()).add(term);
        }

        // 4) 성분 크기 ≥ minCluster 인 것만 이슈로
        List<DerivedIssue> issues = new ArrayList<>();
        for (Set<String> cluster : components.values()) {
            if (cluster.size() < minCluster) {
                continue;
            }
            List<String> top3 = cluster.stream()
                    .sorted(Comparator.comparingInt((String t) -> nodeWeight.getOrDefault(t, 0))
                            .reversed()
                            .thenComparing(Comparator.naturalOrder()))
                    .limit(3)
                    .toList();
            List<Long> articleIds = articlesContaining(ctx.articleKeywords(), cluster);
            Double delta = averageDelta(cluster, ctx.keywordWowDelta());
            issues.add(new DerivedIssue(top3, articleIds, delta));
        }
        // delta 내림차순(NULL 뒤로) 안정 정렬
        issues.sort(Comparator.comparing(
                (DerivedIssue i) -> i.delta(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return issues;
    }

    /** 성분 키워드를 1개 이상 포함한 기사 id(정렬·중복 제거). */
    private static List<Long> articlesContaining(
            Map<Long, List<String>> articleKeywords, Set<String> cluster) {
        Set<Long> ids = new TreeSet<>();
        for (Map.Entry<Long, List<String>> e : articleKeywords.entrySet()) {
            if (e.getValue().stream().anyMatch(cluster::contains)) {
                ids.add(e.getKey());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 멤버 키워드 WoW delta(non-null) 평균. 전부 null이면 null. */
    private static Double averageDelta(Set<String> cluster, Map<String, Double> keywordDelta) {
        double sum = 0;
        int n = 0;
        for (String term : cluster) {
            Double d = keywordDelta.get(term);
            if (d != null) {
                sum += d;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private record Pair(String a, String b) {
        static Pair of(String x, String y) {
            return x.compareTo(y) <= 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    /** 키워드 union-find (path compression). */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        String find(String x) {
            String p = parent.putIfAbsent(x, x);
            if (p == null || p.equals(x)) {
                return x;
            }
            String root = find(p);
            parent.put(x, root);
            return root;
        }

        void union(String x, String y) {
            String rx = find(x);
            String ry = find(y);
            if (!rx.equals(ry)) {
                parent.put(rx, ry);
            }
        }
    }
}
