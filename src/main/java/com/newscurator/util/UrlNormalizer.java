package com.newscurator.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * URL 7단계 정규화: dedup 기준 normalized_url 생성용.
 *
 * <ol>
 *   <li>null/blank 방어
 *   <li>스킴 통일 (http → https)
 *   <li>호스트 소문자화
 *   <li>www. 제거
 *   <li>트래킹 파라미터 제거
 *   <li>쿼리 파라미터 알파벳 순 정렬 (결정론적 비교)
 *   <li>끝 슬래시 제거 (경로에서)
 * </ol>
 */
@Component
public class UrlNormalizer {

    private static final Set<String> TRACKING_PARAMS =
            Set.of(
                    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                    "utm_id", "fbclid", "gclid", "msclkid", "yclid", "ref", "referrer",
                    "source", "campaign");

    public String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }

        String url = rawUrl.trim();

        try {
            // 1. 스킴 통일: http → https
            if (url.startsWith("http://")) {
                url = "https://" + url.substring(7);
            }

            URI uri = new URI(url);

            // 2. 호스트 소문자화
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";

            // 3. www. 제거
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // 4. 경로 끝 슬래시 제거
            String path = uri.getRawPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // 5. 트래킹 파라미터 제거 + 6. 쿼리 파라미터 알파벳 순 정렬
            String query = buildCleanSortedQuery(uri.getRawQuery());

            // 최종 URL 조합 (프래그먼트 제거)
            UriComponentsBuilder builder =
                    UriComponentsBuilder.newInstance()
                            .scheme("https")
                            .host(host)
                            .replacePath(path);

            if (uri.getPort() > 0
                    && uri.getPort() != 443
                    && uri.getPort() != 80) {
                builder.port(uri.getPort());
            }

            if (query != null && !query.isEmpty()) {
                builder.replaceQuery(query);
            }

            return builder.build(true).toUriString();

        } catch (URISyntaxException e) {
            // 파싱 불가 URL은 원본 반환 (dedup 실패보다 저장 우선)
            return url;
        }
    }

    private String buildCleanSortedQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        Arrays.stream(rawQuery.split("&"))
                .forEach(
                        param -> {
                            int eq = param.indexOf('=');
                            String key = eq >= 0 ? param.substring(0, eq) : param;
                            String value = eq >= 0 ? param.substring(eq + 1) : "";
                            if (!TRACKING_PARAMS.contains(key.toLowerCase())) {
                                sorted.put(key, value);
                            }
                        });

        if (sorted.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sorted.forEach(
                (k, v) -> {
                    if (!sb.isEmpty()) sb.append('&');
                    sb.append(k);
                    if (!v.isEmpty()) sb.append('=').append(v);
                });
        return sb.toString();
    }
}
