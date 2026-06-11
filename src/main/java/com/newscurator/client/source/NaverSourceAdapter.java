package com.newscurator.client.source;

import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NaverSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(NaverSourceAdapter.class);

    private static final DateTimeFormatter NAVER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final RestClient restClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;

    public NaverSourceAdapter(
            RestClient restClient,
            @Value("${app.client.naver.base-url}") String baseUrl,
            @Value("${app.client.naver.client-id}") String clientId,
            @Value("${app.client.naver.client-secret}") String clientSecret) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public boolean supports(SourceAdapterType adapterType) {
        return SourceAdapterType.NAVER == adapterType;
    }

    @Override
    public List<ArticleCandidate> fetchCandidates(Source source) {
        try {
            NaverSearchResponse response =
                    restClient
                            .get()
                            .uri(
                                    baseUrl + "/v1/search/news.json?query={query}&display=100",
                                    source.getFeedUrl())
                            .header("X-Naver-Client-Id", clientId)
                            .header("X-Naver-Client-Secret", clientSecret)
                            .retrieve()
                            .body(NaverSearchResponse.class);

            if (response == null || response.items() == null) {
                return Collections.emptyList();
            }

            return response.items().stream()
                    .map(item -> new ArticleCandidate(
                            cleanUrl(item.originallink()),
                            cleanHtml(item.title()),
                            null,
                            parseDate(item.pubDate()),
                            cleanHtml(item.description())))
                    .toList();

        } catch (Exception e) {
            log.warn("[NAVER] 출처 수집 실패, sourceId={}, query={}: {}",
                    source.getId(), source.getFeedUrl(), e.getMessage());
            throw new RuntimeException("[NAVER] 수집 실패: " + source.getFeedUrl(), e);
        }
    }

    private String cleanHtml(String text) {
        if (text == null) return null;
        return text.replaceAll("<[^>]+>", "").trim();
    }

    private String cleanUrl(String url) {
        return url != null ? url : "";
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null) return null;
        try {
            return OffsetDateTime.parse(dateStr, NAVER_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    record NaverSearchResponse(
            int total,
            int start,
            int display,
            List<NaverItem> items) {}

    record NaverItem(
            String title,
            String originallink,
            String link,
            String description,
            String pubDate) {}
}
