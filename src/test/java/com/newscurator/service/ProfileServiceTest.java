package com.newscurator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.newscurator.domain.Account;
import com.newscurator.domain.ReadingPreference;
import com.newscurator.domain.enums.ConsumeMode;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.domain.enums.SummaryDepth;
import com.newscurator.dto.request.ReadingPreferenceRequest;
import com.newscurator.dto.response.ReadingPreferenceResponse;
import com.newscurator.repository.BriefingSettingsRepository;
import com.newscurator.repository.FollowKeywordRepository;
import com.newscurator.repository.ReadingPreferenceRepository;
import com.newscurator.repository.UserInterestsRepository;
import com.newscurator.repository.UserProfileRepository;
import com.newscurator.repository.VoiceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * ProfileService 읽기 방식 업데이트 — 실엔티티(ReadingPreference) 기반 단언.
 * voiceId partial-update 계약: null 전송 시 기존 값 보존, non-null 시 교체.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfileServiceTest {

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private UserInterestsRepository userInterestsRepository;
    @Mock private FollowKeywordRepository followKeywordRepository;
    @Mock private ReadingPreferenceRepository readingPreferenceRepository;
    @Mock private BriefingSettingsRepository briefingSettingsRepository;
    @Mock private VoiceRepository voiceRepository;

    private ProfileService profileService;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        VoiceService voiceService = new VoiceService(voiceRepository);
        profileService = new ProfileService(
                userProfileRepository, userInterestsRepository, followKeywordRepository,
                readingPreferenceRepository, briefingSettingsRepository, voiceService);

        testAccount = Account.builder()
                .email("test@example.com")
                .passwordHash("hash")
                .emailVerified(true)
                .signupType(SignupType.EMAIL)
                .build();

        // save()는 호출되지만 반환값을 사용하지 않으므로 기본 null 반환으로 충분
        when(readingPreferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────
    // (a) voiceId 지정 → 응답에 voiceId 반영 확인
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) voiceId 지정 PUT → 응답 voiceId 포함")
    void updateReadingPreference_withVoiceId_voiceIdReflectedInResponse() {
        when(voiceRepository.existsById("Seoyeon")).thenReturn(true);
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.empty());

        ReadingPreferenceRequest req =
                new ReadingPreferenceRequest(SummaryDepth.BALANCED, ConsumeMode.LISTEN, "Seoyeon");
        ReadingPreferenceResponse response = profileService.updateReadingPreference(testAccount, req);

        assertThat(response.voiceId()).isEqualTo("Seoyeon");
        assertThat(response.summaryDepth()).isEqualTo(SummaryDepth.BALANCED);
        assertThat(response.consumeMode()).isEqualTo(ConsumeMode.LISTEN);
    }

    // ─────────────────────────────────────────────────────────
    // (b) 기존 voiceId='Seoyeon' + voiceId null PUT → voiceId 'Seoyeon' 보존
    //     (full-replace wipe 버그 방지 핵심 케이스)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) 기존 voiceId='Seoyeon' + voiceId null PUT → 기존 voiceId 보존(wipe 없음)")
    void updateReadingPreference_nullVoiceId_preservesExistingVoiceId() {
        // 기존 행: summaryDepth=BRIEF, consumeMode=LISTEN, voiceId='Seoyeon'
        ReadingPreference existing = ReadingPreference.builder()
                .account(testAccount)
                .summaryDepth(SummaryDepth.BRIEF)
                .consumeMode(ConsumeMode.LISTEN)
                .build();
        existing.update(SummaryDepth.BRIEF, ConsumeMode.LISTEN, "Seoyeon"); // voiceId 초기 세팅
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.of(existing));

        // voiceId 없이 summaryDepth만 변경하는 PUT
        ReadingPreferenceRequest req =
                new ReadingPreferenceRequest(SummaryDepth.BALANCED, ConsumeMode.LISTEN, null);
        ReadingPreferenceResponse response = profileService.updateReadingPreference(testAccount, req);

        // voiceId는 wipe되지 않고 'Seoyeon' 그대로 보존되어야 함
        assertThat(response.voiceId()).isEqualTo("Seoyeon");
        assertThat(response.summaryDepth()).isEqualTo(SummaryDepth.BALANCED); // 변경됨
        assertThat(response.consumeMode()).isEqualTo(ConsumeMode.LISTEN);     // 유지됨
    }

    // ─────────────────────────────────────────────────────────
    // (c) consumeMode·summaryDepth가 기존 값에서 의도대로 갱신되는지
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) summaryDepth/consumeMode full-replace — 기존 값에서 다른 값으로 갱신 확인")
    void updateReadingPreference_summaryDepthAndConsumeMode_replaced() {
        ReadingPreference existing = ReadingPreference.builder()
                .account(testAccount)
                .summaryDepth(SummaryDepth.BALANCED)
                .consumeMode(ConsumeMode.READ)
                .build();
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.of(existing));

        ReadingPreferenceRequest req =
                new ReadingPreferenceRequest(SummaryDepth.BRIEF, ConsumeMode.BOTH, null);
        ReadingPreferenceResponse response = profileService.updateReadingPreference(testAccount, req);

        assertThat(response.summaryDepth()).isEqualTo(SummaryDepth.BRIEF);   // 변경됨
        assertThat(response.consumeMode()).isEqualTo(ConsumeMode.BOTH);       // 변경됨
        assertThat(response.voiceId()).isNull();                               // 기존 null 유지
    }

    // ─────────────────────────────────────────────────────────
    // (d) 존재하지 않는 voiceId → IllegalArgumentException (422)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) 존재하지 않는 voiceId → IllegalArgumentException 발생")
    void updateReadingPreference_invalidVoiceId_throwsIllegalArgument() {
        when(voiceRepository.existsById("no-such-speaker")).thenReturn(false);
        when(readingPreferenceRepository.findByAccountId(any())).thenReturn(Optional.empty());

        ReadingPreferenceRequest req =
                new ReadingPreferenceRequest(SummaryDepth.BALANCED, ConsumeMode.READ, "no-such-speaker");

        assertThatThrownBy(() -> profileService.updateReadingPreference(testAccount, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-speaker");
    }
}
