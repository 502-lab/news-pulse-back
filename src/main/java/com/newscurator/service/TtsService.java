package com.newscurator.service;

import com.newscurator.domain.Article;
import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.domain.enums.TtsOwnerType;
import com.newscurator.domain.enums.TtsStatus;
import com.newscurator.dto.response.TtsStatusResponse;
import com.newscurator.exception.ArticleNotFoundException;
import com.newscurator.exception.SummaryNotReadyException;
import com.newscurator.exception.TtsAudioNotFoundException;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.TtsAudioRepository;
import com.newscurator.repository.VoiceRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TtsService {

    private final TtsAudioRepository ttsAudioRepository;
    private final ArticleRepository articleRepository;
    private final VoiceRepository voiceRepository;
    private final S3AudioUploader s3AudioUploader;

    public TtsService(
            TtsAudioRepository ttsAudioRepository,
            ArticleRepository articleRepository,
            VoiceRepository voiceRepository,
            S3AudioUploader s3AudioUploader) {
        this.ttsAudioRepository = ttsAudioRepository;
        this.articleRepository = articleRepository;
        this.voiceRepository = voiceRepository;
        this.s3AudioUploader = s3AudioUploader;
    }

    /**
     * 기사 TTS 요청 — 멱등 4분기.
     *
     * <p>READY → 즉시 반환(no save). PENDING/PROCESSING → 기존 반환(no save).
     * FAILED → resetToPending() + save(same row). 없음 → 신규 PENDING INSERT.
     */
    @Transactional
    public TtsStatusResponse requestArticleTts(Long articleId, String voiceId) {
        if (!voiceRepository.existsById(voiceId)) {
            throw new IllegalArgumentException("존재하지 않는 voiceId: " + voiceId);
        }

        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ArticleNotFoundException(articleId));

        if (article.getSummaryStatus() != ProcessingStatus.COMPLETED) {
            throw new SummaryNotReadyException(
                    "기사 요약이 아직 완료되지 않았습니다: articleId=" + articleId
                            + ", summaryStatus=" + article.getSummaryStatus());
        }

        String refId = String.valueOf(articleId);
        Optional<TtsAudio> existing =
                ttsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, refId, voiceId);

        if (existing.isPresent()) {
            TtsAudio tts = existing.get();
            if (tts.getStatus() == TtsStatus.READY) {
                // READY: 즉시 반환, save 없음
                return toResponse(tts);
            }
            if (tts.getStatus() == TtsStatus.PENDING || tts.getStatus() == TtsStatus.PROCESSING) {
                // PENDING/PROCESSING: 기존 반환, save 없음
                return toResponse(tts);
            }
            // FAILED: 같은 행 재사용 — 신규 INSERT 금지(UNIQUE 위반 방지)
            tts.resetToPending();
            ttsAudioRepository.save(tts);
            return toResponse(tts);
        }

        // 없음: 신규 PENDING INSERT
        TtsAudio newTts = TtsAudio.builder()
                .ownerType(TtsOwnerType.ARTICLE)
                .refId(refId)
                .voiceId(voiceId)
                .build();
        ttsAudioRepository.save(newTts);
        return toResponse(newTts);
    }

    /**
     * 기사 TTS 상태 조회.
     */
    @Transactional(readOnly = true)
    public TtsStatusResponse getArticleTtsStatus(Long articleId, String voiceId) {
        String refId = String.valueOf(articleId);
        TtsAudio tts = ttsAudioRepository
                .findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType.ARTICLE, refId, voiceId)
                .orElseThrow(() -> new TtsAudioNotFoundException(
                        "TTS를 찾을 수 없습니다: articleId=" + articleId + ", voiceId=" + voiceId));
        return toResponse(tts);
    }

    private TtsStatusResponse toResponse(TtsAudio tts) {
        // audioKey가 null이면 generateUrl 호출 없이 null 반환 (PENDING/PROCESSING/FAILED 대응)
        String audioUrl = tts.getAudioKey() != null
                ? s3AudioUploader.generateUrl(tts.getAudioKey())
                : null;
        return new TtsStatusResponse(
                tts.getId(),
                tts.getOwnerType(),
                tts.getRefId(),
                tts.getVoiceId(),
                tts.getStatus(),
                audioUrl,
                tts.getDurationSec(),
                tts.getErrorMsg());
    }
}
