package com.newscurator.client.ai;

import com.newscurator.exception.AiProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Naver Clova Voice Premium TTS API 클라이언트 (NCP 인증 사용).
 *
 * <p>인증: X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY
 * Naver Developers 클라이언트 인증(X-Naver-Client-*)과 완전히 별개 — 재사용 불가.
 *
 * <p>T030에서 실제 HTTP 호출 로직을 완성한다. 이 클래스는 T011 스켈레톤.
 */
@Component
public class NaverClovaVoiceClient {

    private static final Logger log = LoggerFactory.getLogger(NaverClovaVoiceClient.class);

    private final RestClient restClient;
    private final String apiKeyId;
    private final String apiKey;

    public NaverClovaVoiceClient(
            @Value("${naver.clova.voice.base-url}") String baseUrl,
            @Value("${naver.clova.voice.api-key-id}") String apiKeyId,
            @Value("${naver.clova.voice.api-key}") String apiKey) {
        this.apiKeyId = apiKeyId;
        this.apiKey = apiKey;
        // API 키 로그 출력 절대 금지
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        log.info("NaverClovaVoiceClient initialized (base-url={})", baseUrl);
    }

    /**
     * Clova Voice Premium API 1콜당 2000자 제한 대응 트런케이터.
     * 1900자를 초과하면 마지막 문장 경계(. ? !) 이후에서 잘라 "…"를 붙인다.
     * 문장 경계가 없으면 1900자에서 하드컷 + "…".
     */
    public static String truncateTtsText(String text) {
        if (text.length() <= 1900) {
            return text;
        }
        String sub = text.substring(0, 1900);
        int lastBoundary = Math.max(
                Math.max(sub.lastIndexOf('.'), sub.lastIndexOf('?')),
                sub.lastIndexOf('!'));
        if (lastBoundary > 0) {
            return sub.substring(0, lastBoundary + 1) + "…";
        }
        return sub + "…";
    }

    /**
     * 주어진 텍스트를 지정 음성으로 TTS 변환하여 MP3 bytes를 반환한다.
     *
     * @param voiceId Naver Clova Voice speaker ID — TODO(V1): NCP 콘솔 확인 후 실제 ID 사용
     * @param text    TTS 변환 대상 텍스트 (기사 요약)
     * @return MP3 바이너리
     * @throws AiProviderException 4xx/5xx 응답 시
     */
    public byte[] generate(String voiceId, String text) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("speaker", voiceId);
        formData.add("text", truncateTtsText(text));
        formData.add("volume", "0");
        formData.add("speed", "0");
        formData.add("pitch", "0");
        formData.add("format", "mp3");

        try {
            return restClient
                    .post()
                    .uri("/tts-premium/v1/tts")
                    .header("X-NCP-APIGW-API-KEY-ID", apiKeyId)
                    .header("X-NCP-APIGW-API-KEY", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                throw new AiProviderException(
                                        "Naver Clova Voice API error: HTTP " + res.getStatusCode());
                            })
                    .body(byte[].class);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException("Naver Clova Voice API call failed", e);
        }
    }
}
