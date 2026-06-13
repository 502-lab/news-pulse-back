package com.newscurator.scheduler;

import com.newscurator.client.ai.NaverClovaVoiceClient;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.domain.enums.SummarySlotStatus;
import com.newscurator.repository.SummaryRepository;
import com.newscurator.service.S3AudioUploader;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TTS 처리 스케줄러.
 *
 * <p>트랜잭션 경계 설계:
 * Phase 1 — TtsAudioClaimer.claimBatch() (@Transactional 별도 빈): PENDING → PROCESSING 마킹 + save.
 *            TX 커밋 시 FOR UPDATE SKIP LOCKED 락 해제 → 멀티 인스턴스 중복 처리 방지.
 * Phase 2 — Naver HTTP 호출 + S3 업로드: 락 해제 후 실행 (5~15초 외부 호출, DB 락 불점유).
 * Phase 3 — TtsAudioClaimer.persistResult() (@Transactional 별도 빈): 결과 저장 (READY/FAILED).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TtsProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TtsProcessingScheduler.class);

    private final TtsAudioClaimer claimer;
    private final NaverClovaVoiceClient naverClovaVoiceClient;
    private final S3AudioUploader s3AudioUploader;
    private final SummaryRepository summaryRepository;
    private final int batchSize;

    public TtsProcessingScheduler(
            TtsAudioClaimer claimer,
            NaverClovaVoiceClient naverClovaVoiceClient,
            S3AudioUploader s3AudioUploader,
            SummaryRepository summaryRepository,
            @Value("${app.tts.scheduler.batch-size:10}") int batchSize) {
        this.claimer = claimer;
        this.naverClovaVoiceClient = naverClovaVoiceClient;
        this.s3AudioUploader = s3AudioUploader;
        this.summaryRepository = summaryRepository;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.tts.scheduler.cron}")
    public void process() {
        // Phase 1: claim batch — 짧은 @Transactional, 반환 시 FOR UPDATE 락 해제됨
        List<TtsAudio> batch = claimer.claimBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.info("[TTS] 배치 클레임: {}건", batch.size());

        // Phase 2+3: 각 항목 독립 처리 — Naver HTTP는 락 밖에서 실행
        for (TtsAudio tts : batch) {
            String audioKey = buildAudioKey(tts);
            try {
                String ttsText = resolveTtsText(tts);
                byte[] mp3 = naverClovaVoiceClient.generate(tts.getVoiceId(), ttsText);
                s3AudioUploader.upload(mp3, audioKey);
                tts.complete(audioKey, null);
                log.info("[TTS] READY: id={}, key={}", tts.getId(), audioKey);
            } catch (Exception e) {
                log.warn("[TTS] FAILED: id={}: {}", tts.getId(), e.getMessage());
                tts.fail(e.getMessage());
            }
            // Phase 3: 결과 저장 — 자체 @Transactional
            claimer.persistResult(tts);
        }
    }

    private String buildAudioKey(TtsAudio tts) {
        return "tts/article/" + tts.getRefId() + "/" + tts.getVoiceId() + ".mp3";
    }

    private String resolveTtsText(TtsAudio tts) {
        long articleId = Long.parseLong(tts.getRefId());
        // BALANCED 요약 우선, 없으면 BRIEF 사용; 둘 다 없으면 예외 → FAILED 저장
        return summaryRepository.findByArticleIdAndDepth(articleId, SummaryDepth.BALANCED)
                .filter(s -> s.getStatus() == SummarySlotStatus.COMPLETED)
                .map(s -> s.getContent())
                .or(() -> summaryRepository.findByArticleIdAndDepth(articleId, SummaryDepth.BRIEF)
                        .filter(s -> s.getStatus() == SummarySlotStatus.COMPLETED)
                        .map(s -> s.getContent()))
                .orElseThrow(() -> new IllegalStateException(
                        "처리 가능한 완료 요약 없음: refId=" + tts.getRefId()));
    }
}
