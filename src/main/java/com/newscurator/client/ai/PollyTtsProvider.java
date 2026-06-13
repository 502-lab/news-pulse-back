package com.newscurator.client.ai;

import com.newscurator.exception.AiProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.polly.model.Engine;
import software.amazon.awssdk.services.polly.model.OutputFormat;
import software.amazon.awssdk.services.polly.model.PollyException;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest;
import software.amazon.awssdk.services.polly.model.VoiceId;

/**
 * AWS Polly Neural TTS 제공자.
 *
 * <p>Polly 요청당 최대 약 3,000자 한도 대응: 2,900자 초과 시 마지막 문장 경계(. ? !)에서 트런케이트 후 "…" 부착.
 * 인증: DefaultCredentialsProvider(EC2 인스턴스 프로파일 또는 환경변수 AWS_ACCESS_KEY_ID 등).
 */
@Service
public class PollyTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(PollyTtsProvider.class);
    private static final int MAX_CHARS = 2900;

    private final PollyClient pollyClient;
    private final Engine engine;

    public PollyTtsProvider(
            @Value("${cloud.aws.region}") String region,
            @Value("${app.tts.polly.engine:neural}") String engineName) {
        this.pollyClient = PollyClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .build();
        this.engine = Engine.fromValue(engineName);
        log.info("PollyTtsProvider initialized (region={}, engine={})", region, engineName);
    }

    /**
     * 2,900자 초과 시 마지막 문장 경계에서 트런케이트.
     * NaverClovaVoiceClient의 동일 로직 포팅 (한도만 1900→2900으로 확장).
     */
    public static String truncateTtsText(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        String sub = text.substring(0, MAX_CHARS);
        int lastBoundary = Math.max(
                Math.max(sub.lastIndexOf('.'), sub.lastIndexOf('?')),
                sub.lastIndexOf('!'));
        if (lastBoundary > 0) {
            return sub.substring(0, lastBoundary + 1) + "…";
        }
        return sub + "…";
    }

    @Override
    public byte[] generate(String voiceId, String text) {
        String truncated = truncateTtsText(text);
        SynthesizeSpeechRequest request = SynthesizeSpeechRequest.builder()
                .voiceId(VoiceId.fromValue(voiceId))
                .engine(engine)
                .outputFormat(OutputFormat.MP3)
                .text(truncated)
                .build();
        try (var response = pollyClient.synthesizeSpeech(request)) {
            return response.readAllBytes();
        } catch (PollyException e) {
            throw new AiProviderException("AWS Polly API error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AiProviderException("AWS Polly TTS call failed", e);
        }
    }
}
