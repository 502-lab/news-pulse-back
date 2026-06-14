package com.newscurator.service;

import com.newscurator.dto.response.VoiceResponse;
import com.newscurator.repository.VoiceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceService {

    private final VoiceRepository voiceRepository;

    public VoiceService(VoiceRepository voiceRepository) {
        this.voiceRepository = voiceRepository;
    }

    @Transactional(readOnly = true)
    public List<VoiceResponse> findAll() {
        return voiceRepository.findAll().stream()
                .map(v -> new VoiceResponse(v.getId(), v.getName(), v.getGender(), v.getPreviewUrl()))
                .toList();
    }

    // VoiceRepository.existsById() 기반 검증 — false면 IllegalArgumentException (GlobalExceptionHandler → 422)
    public void validateVoiceId(String voiceId) {
        if (!voiceRepository.existsById(voiceId)) {
            throw new IllegalArgumentException("존재하지 않는 voiceId: " + voiceId);
        }
    }
}
