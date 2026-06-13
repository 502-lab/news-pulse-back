package com.newscurator.repository;

import com.newscurator.domain.TtsAudio;
import com.newscurator.domain.enums.TtsOwnerType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TtsAudioRepository extends JpaRepository<TtsAudio, UUID> {

    Optional<TtsAudio> findByOwnerTypeAndRefIdAndVoiceId(
            TtsOwnerType ownerType, String refId, String voiceId);

    // SELECT ... FOR UPDATE SKIP LOCKED: 멀티 인스턴스 동시 실행 시 중복 처리 방지 (이중 과금 방지)
    // 단순 findByStatus(PENDING) 폴링 절대 사용 금지
    @Query(
            value =
                    "SELECT * FROM tts_audios "
                            + "WHERE status = 'PENDING' "
                            + "LIMIT :limit "
                            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<TtsAudio> findPendingWithLock(@Param("limit") int limit);
}
