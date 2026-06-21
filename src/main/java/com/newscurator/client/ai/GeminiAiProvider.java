package com.newscurator.client.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.config.AiProperties;
import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.exception.AiProviderException;
import com.newscurator.exception.AiTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class GeminiAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiProvider.class);

    private static final String CLASSIFY_PROMPT =
            "다음 뉴스 기사를 아래 카테고리 중 하나로 분류하세요. "
                    + "반드시 카테고리 이름만 영어로 답하세요 (다른 텍스트 없이).\n"
                    + "카테고리: ECONOMY_FINANCE, SCIENCE, POLITICS, SPORTS, WORLD, "
                    + "ENTERTAINMENT_CULTURE, HEALTH_MEDICINE, AUTOMOTIVE, IT, OTHER\n\n"
                    + "제목: %s\n내용: %s";

    private static final String SUMMARIZE_BRIEF_PROMPT =
            "다음 뉴스 기사를 한국어로 100자 이내로 핵심만 요약하세요.\n제목: %s\n내용: %s";

    private static final String SUMMARIZE_BALANCED_PROMPT =
            "다음 뉴스 기사를 한국어로 200~300자로 균형 있게 요약하세요.\n제목: %s\n내용: %s";

    private static final String SUMMARIZE_DEEP_PROMPT =
            "다음 뉴스 기사를 한국어로 500~700자로 심층 분석하여 요약하세요.\n제목: %s\n내용: %s";

    private static final String BIAS_PROMPT =
            "다음 뉴스 기사의 정치적 편향성을 분석하세요. "
                    + "반드시 아래 JSON 형식으로만 답하세요 (다른 텍스트 없이):\n"
                    + "{\"score\": <-100에서 +100 사이 정수>, "
                    + "\"keywords\": [\"키워드1\", \"키워드2\", ...]}\n"
                    + "score: -100=극진보, 0=중립, +100=극보수 (한국 뉴스 생태계 기준)\n"
                    + "keywords: 편향의 근거 키워드 2~5개\n"
                    + "순수 사실 보도(날씨·스포츠 결과 등)는 score=0, keywords=[\"사실 보도\"]\n\n"
                    + "제목: %s\n내용: %s";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public GeminiAiProvider(
            RestClient restClient,
            @Value("${app.client.gemini.api-key}") String apiKey,
            @Value("${app.client.gemini.model:gemini-2.0-flash}") String model,
            @Value("${app.client.gemini.base-url}") String baseUrl,
            AiProperties aiProperties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Category classify(String title, String content) {
        String prompt = CLASSIFY_PROMPT.formatted(
                truncate(title, 200), truncate(content, 1000));
        String response = callGemini(prompt);
        return parseCategory(response);
    }

    @Override
    public String summarize(String title, String content, SummaryDepth depth) {
        String promptTemplate =
                switch (depth) {
                    case BRIEF -> SUMMARIZE_BRIEF_PROMPT;
                    case BALANCED -> SUMMARIZE_BALANCED_PROMPT;
                    case DEEP -> SUMMARIZE_DEEP_PROMPT;
                };
        String prompt = promptTemplate.formatted(
                truncate(title, 200), truncate(content, 3000));
        return callGemini(prompt);
    }

    @Override
    public BiasAnalysisResult analyzeBias(String title, String content) {
        String prompt = BIAS_PROMPT.formatted(truncate(title, 200), truncate(content, 3000));
        String response = callGemini(prompt);
        return parseBias(response);
    }

    /**
     * Gemini JSON 응답을 파싱한다. 마크다운 코드펜스(```json ... ```)를 제거하고
     * {"score": int, "keywords": [..]} 구조를 검증한다. 범위·개수 위반·파싱 실패는
     * 결정적 오류이므로 {@link AiProviderException}(재시도 비대상).
     */
    private BiasAnalysisResult parseBias(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiProviderException("편향 분석 응답이 비어 있음");
        }
        String json = stripCodeFence(raw.trim());
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode scoreNode = root.get("score");
            JsonNode keywordsNode = root.get("keywords");
            if (scoreNode == null || !scoreNode.isInt() && !scoreNode.canConvertToInt()) {
                throw new AiProviderException("score 필드 누락/비정수: " + json);
            }
            int score = scoreNode.intValue();
            if (score < -100 || score > 100) {
                throw new AiProviderException("score 범위 초과(-100~100): " + score);
            }
            if (keywordsNode == null || !keywordsNode.isArray()) {
                throw new AiProviderException("keywords 필드 누락/배열 아님: " + json);
            }
            List<String> keywords = new ArrayList<>();
            for (JsonNode k : keywordsNode) {
                if (k.isTextual() && !k.asText().isBlank()) {
                    keywords.add(k.asText());
                }
            }
            if (keywords.size() < 2 || keywords.size() > 5) {
                throw new AiProviderException("keywords 개수 범위 초과(2~5): " + keywords.size());
            }
            return new BiasAnalysisResult(score, keywords);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException("편향 분석 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private String stripCodeFence(String text) {
        String t = text;
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    private String callGemini(String prompt) {
        try {
            if (aiProperties.delayBetweenCallsMs() > 0) {
                sleep(aiProperties.delayBetweenCallsMs());
            }

            Map<String, Object> request = Map.of(
                    "contents",
                    List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

            GeminiResponse response = restClient
                    .post()
                    .uri(baseUrl + "/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse.class);

            if (response == null
                    || response.candidates() == null
                    || response.candidates().isEmpty()) {
                throw new AiProviderException("Empty Gemini response");
            }

            return response.candidates().get(0).content().parts().get(0).text();

        } catch (HttpClientErrorException.TooManyRequests e) {
            String retryAfter = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("Retry-After") : null;
            log.warn("[GEMINI] 429 Rate limit, Retry-After={}", retryAfter);
            throw new AiTransientException("429 Rate limit", e);
        } catch (HttpServerErrorException e) {
            log.warn("[GEMINI] 5xx 서버 오류: {}", e.getStatusCode());
            throw new AiTransientException("5xx 서버 오류: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            log.warn("[GEMINI] 연결/타임아웃 오류: {}", e.getMessage());
            throw new AiTransientException("연결/타임아웃 오류: " + e.getMessage(), e);
        } catch (AiProviderException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw new AiProviderException("결정적 HTTP 오류: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new AiProviderException("Gemini API call failed: " + e.getMessage(), e);
        }
    }

    private Category parseCategory(String raw) {
        if (raw == null) return Category.OTHER;
        String trimmed = raw.trim().toUpperCase().replaceAll("[^A-Z_]", "");
        try {
            return Category.valueOf(trimmed);
        } catch (IllegalArgumentException e) {
            // enum 외 카테고리 값 → OTHER 폴백 + WARN 로그 (category_raw_value 포함)
            log.warn("[GEMINI] 알 수 없는 카테고리 값, category_raw_value={}, fallback=OTHER", raw);
            return Category.OTHER;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    record GeminiResponse(List<Candidate> candidates) {
        record Candidate(Content content) {}

        record Content(List<Part> parts) {}

        record Part(String text) {}
    }
}
