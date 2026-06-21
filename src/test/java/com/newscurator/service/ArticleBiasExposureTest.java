package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.dto.response.ArticleDetailResponse;
import com.newscurator.dto.response.ArticleFeedItem;
import com.newscurator.dto.response.BiasScoreResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T027: SC-002 — biasScore 필드가 응답에 '항상 포함'되며(누락 0%), status≠DONE이면 값만 null임을 검증.
 * 필드 존재 ≠ 값 존재.
 */
class ArticleBiasExposureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── toResponse 매핑 ───────────────────────────────────────────────

    @Test
    @DisplayName("매핑: DONE → value·keywords·status 채움")
    void mapping_done() {
        BiasAnalysis row = BiasAnalysis.builder().articleId(1L).build();
        row.complete(-45, new String[] {"k1", "k2"});

        BiasScoreResponse r = BiasAnalysisService.toResponse(row);

        assertThat(r.value()).isEqualTo(-45);
        assertThat(r.rationaleKeywords()).containsExactly("k1", "k2");
        assertThat(r.status()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("매핑: PENDING → value·keywords null, status만")
    void mapping_pending() {
        BiasAnalysis row = BiasAnalysis.builder().articleId(1L).build(); // 기본 PENDING

        BiasScoreResponse r = BiasAnalysisService.toResponse(row);

        assertThat(r.value()).isNull();
        assertThat(r.rationaleKeywords()).isNull();
        assertThat(r.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("매핑: 행 없음(null) → null (필드는 응답에 존재하되 값 null)")
    void mapping_nullRow() {
        assertThat(BiasAnalysisService.toResponse(null)).isNull();
    }

    // ── JSON 직렬화: 필드 누락(omit) 금지 검증 ────────────────────────

    @Test
    @DisplayName("피드 항목: status≠DONE이면 biasScore 필드 포함 + value=null (omit 아님)")
    void feedItem_pending_fieldIncludedValueNull() throws Exception {
        ArticleFeedItem item = new ArticleFeedItem(
                1L, "title", null, "IT", null, null, null,
                new BiasScoreResponse(null, null, "PENDING"));

        String json = mapper.writeValueAsString(item);

        assertThat(json).contains("\"biasScore\"");      // 필드 존재
        assertThat(json).contains("\"status\":\"PENDING\"");
        assertThat(json).contains("\"value\":null");     // 값은 null (생략 아님)
    }

    @Test
    @DisplayName("피드 항목: BiasAnalysis 행 없음 → biasScore:null 키 존재(omit 아님)")
    void feedItem_noRow_fieldPresentNull() throws Exception {
        ArticleFeedItem item = new ArticleFeedItem(
                1L, "title", null, "IT", null, null, null, null);

        String json = mapper.writeValueAsString(item);

        assertThat(json).contains("\"biasScore\":null"); // 키 존재, 값 null
    }

    @Test
    @DisplayName("상세 응답: status≠DONE이면 biasScore 필드 포함 + value=null")
    void detail_pending_fieldIncludedValueNull() throws Exception {
        ArticleDetailResponse resp = new ArticleDetailResponse(
                1L, "title", null, "https://e.com", "IT", null, null,
                null, null, null,
                new BiasScoreResponse(null, null, "FAILED"));

        String json = mapper.writeValueAsString(resp);

        assertThat(json).contains("\"biasScore\"");
        assertThat(json).contains("\"status\":\"FAILED\"");
        assertThat(json).contains("\"value\":null");
    }

    // ── 결정적 증명: 글로벌 inclusion=NON_NULL 으로 구성된 mapper로도 필드 유지 ──
    // @JsonInclude(ALWAYS)가 글로벌 설정을 override 하므로 mapper 구성과 무관하게 SC-002 성립.

    @Test
    @DisplayName("글로벌 NON_NULL mapper: 피드 biasScore:null + value:null 여전히 포함(omit 아님)")
    void feedItem_underGlobalNonNull_stillIncluded() throws Exception {
        ObjectMapper nonNull = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 행 없음 → biasScore:null 키 유지
        String jsonNoRow = nonNull.writeValueAsString(new ArticleFeedItem(
                1L, "title", null, "IT", null, null, null, null));
        assertThat(jsonNoRow).contains("\"biasScore\":null");

        // status≠DONE → biasScore 포함 + value:null 유지
        String jsonPending = nonNull.writeValueAsString(new ArticleFeedItem(
                1L, "title", null, "IT", null, null, null,
                new BiasScoreResponse(null, null, "PENDING")));
        assertThat(jsonPending).contains("\"biasScore\"");
        assertThat(jsonPending).contains("\"value\":null");
        assertThat(jsonPending).contains("\"status\":\"PENDING\"");
    }

    @Test
    @DisplayName("글로벌 NON_NULL mapper: 상세 biasScore + value:null 여전히 포함")
    void detail_underGlobalNonNull_stillIncluded() throws Exception {
        ObjectMapper nonNull = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        String json = nonNull.writeValueAsString(new ArticleDetailResponse(
                1L, "title", null, "https://e.com", "IT", null, null,
                null, null, null,
                new BiasScoreResponse(null, null, "FAILED")));

        assertThat(json).contains("\"biasScore\"");
        assertThat(json).contains("\"value\":null");
        assertThat(json).contains("\"status\":\"FAILED\"");
    }
}
