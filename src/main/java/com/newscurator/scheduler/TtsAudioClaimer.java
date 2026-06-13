package com.newscurator.scheduler;

import com.newscurator.domain.TtsAudio;
import com.newscurator.repository.TtsAudioRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TTS 배치 클레임 전담 컴포넌트.
 *
 * <p>별도 Spring 빈으로 분리하여 @Transactional이 AOP 프록시를 통해 정상 적용되도록 한다.
 * claimBatch()의 TX가 커밋되면 FOR UPDATE SKIP LOCKED 락이 해제되고,
 * 이후 Naver HTTP 호출이 DB 락 없이 실행된다.
 */
@Component
class TtsAudioClaimer {

    private final TtsAudioRepository ttsAudioRepository;

    TtsAudioClaimer(TtsAudioRepository ttsAudioRepository) {
        this.ttsAudioRepository = ttsAudioRepository;
    }

    /**
     * PENDING 배치를 PROCESSING으로 마킹하고 커밋한다.
     * 이 메서드가 반환된 시점에 FOR UPDATE 락이 해제된다.
     */
    @Transactional
    public List<TtsAudio> claimBatch(int limit) {
        List<TtsAudio> batch = ttsAudioRepository.findPendingWithLock(limit);
        for (TtsAudio tts : batch) {
            tts.markProcessing();
            ttsAudioRepository.save(tts);
        }
        return new ArrayList<>(batch);
    }

    /**
     * 처리 결과(READY 또는 FAILED)를 자체 트랜잭션으로 저장한다.
     */
    @Transactional
    public void persistResult(TtsAudio tts) {
        ttsAudioRepository.save(tts);
    }
}
