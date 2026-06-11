package com.newscurator.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    private UrlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new UrlNormalizer();
    }

    @Test
    @DisplayName("http를 https로 통일")
    void normalize_httpToHttps() {
        String result = normalizer.normalize("http://example.com/news/123");
        assertThat(result).startsWith("https://");
    }

    @Test
    @DisplayName("www. 접두사 제거")
    void normalize_removeWww() {
        String result = normalizer.normalize("https://www.example.com/news");
        assertThat(result).doesNotContain("www.");
    }

    @Test
    @DisplayName("끝 슬래시 제거")
    void normalize_removeTrailingSlash() {
        String result = normalizer.normalize("https://example.com/news/");
        assertThat(result).doesNotEndWith("/");
    }

    @Test
    @DisplayName("UTM 트래킹 파라미터 제거")
    void normalize_removeUtmParams() {
        String result =
                normalizer.normalize(
                        "https://example.com/news?id=123&utm_source=google&utm_medium=cpc");
        assertThat(result).doesNotContain("utm_source").doesNotContain("utm_medium");
        assertThat(result).contains("id=123");
    }

    @Test
    @DisplayName("fbclid 트래킹 파라미터 제거")
    void normalize_removeFbclid() {
        String result = normalizer.normalize("https://example.com/news?fbclid=abc123&page=1");
        assertThat(result).doesNotContain("fbclid").contains("page=1");
    }

    @Test
    @DisplayName("같은 URL을 다른 순서로 입력해도 동일 결과")
    void normalize_sortQueryParams() {
        String url1 = normalizer.normalize("https://example.com/news?z=1&a=2");
        String url2 = normalizer.normalize("https://example.com/news?a=2&z=1");
        assertThat(url1).isEqualTo(url2);
    }

    @Test
    @DisplayName("호스트 소문자화")
    void normalize_lowercaseHost() {
        String result = normalizer.normalize("https://Example.COM/news");
        assertThat(result).containsIgnoringCase("example.com");
        assertThat(result).doesNotContain("Example.COM");
    }

    @Test
    @DisplayName("null URL은 예외 발생")
    void normalize_nullUrl_throwsException() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 URL은 예외 발생")
    void normalize_blankUrl_throwsException() {
        assertThatThrownBy(() -> normalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("http→https 변환 후 동일 URL은 동일 정규화 결과")
    void normalize_httpAndHttpsSameUrl_equalResult() {
        String http = normalizer.normalize("http://example.com/news/123");
        String https = normalizer.normalize("https://example.com/news/123");
        assertThat(http).isEqualTo(https);
    }

    @Test
    @DisplayName("프래그먼트(#) 제거")
    void normalize_removeFragment() {
        String result = normalizer.normalize("https://example.com/news/123#section1");
        assertThat(result).doesNotContain("#");
    }
}
