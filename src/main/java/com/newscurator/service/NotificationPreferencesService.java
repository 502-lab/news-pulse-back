package com.newscurator.service;

import com.newscurator.domain.NotificationPreferences;
import com.newscurator.dto.request.NotificationSettingsRequest;
import com.newscurator.repository.NotificationPreferencesRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPreferencesService {

    private final NotificationPreferencesRepository preferencesRepository;

    public NotificationPreferencesService(NotificationPreferencesRepository preferencesRepository) {
        this.preferencesRepository = preferencesRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPreferences getOrDefault(UUID accountId) {
        return preferencesRepository.findById(accountId)
                .orElseGet(() -> new NotificationPreferences(accountId));
    }

    @Transactional
    public NotificationPreferences update(UUID accountId, NotificationSettingsRequest request) {
        NotificationPreferences prefs = preferencesRepository.findById(accountId)
                .orElseGet(() -> new NotificationPreferences(accountId));
        prefs.update(request.pushEnabled(), request.emailEnabled(), request.risingEnabled(), request.biasEnabled());
        return preferencesRepository.save(prefs);
    }
}
