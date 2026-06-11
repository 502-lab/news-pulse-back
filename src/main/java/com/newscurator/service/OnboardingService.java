package com.newscurator.service;

import com.newscurator.domain.*;
import com.newscurator.dto.request.OnboardingRequest;
import com.newscurator.dto.response.OnboardingStatusResponse;
import com.newscurator.repository.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class OnboardingService {

    private final UserProfileRepository userProfileRepository;
    private final UserInterestsRepository userInterestsRepository;
    private final FollowKeywordRepository followKeywordRepository;
    private final ReadingPreferenceRepository readingPreferenceRepository;
    private final BriefingSettingsRepository briefingSettingsRepository;
    private final AccountRepository accountRepository;

    public OnboardingService(UserProfileRepository userProfileRepository,
                             UserInterestsRepository userInterestsRepository,
                             FollowKeywordRepository followKeywordRepository,
                             ReadingPreferenceRepository readingPreferenceRepository,
                             BriefingSettingsRepository briefingSettingsRepository,
                             AccountRepository accountRepository) {
        this.userProfileRepository = userProfileRepository;
        this.userInterestsRepository = userInterestsRepository;
        this.followKeywordRepository = followKeywordRepository;
        this.readingPreferenceRepository = readingPreferenceRepository;
        this.briefingSettingsRepository = briefingSettingsRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public void submitOnboarding(Account account, OnboardingRequest req) {
        List<String> categories = req.categories();
        if (categories == null || categories.size() < 3) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "관심 카테고리는 최소 3개 이상이어야 합니다");
        }

        // UserProfile — upsert
        UserProfile profile = userProfileRepository.findByAccountId(account.getId())
                .orElseGet(() -> UserProfile.builder().account(account).build());
        profile.update(req.nickname(), req.ageGroup(), req.occupation());
        userProfileRepository.save(profile);

        // UserInterests — replace all
        userInterestsRepository.deleteByAccountId(account.getId());
        for (String category : categories) {
            userInterestsRepository.save(UserInterests.builder()
                    .account(account)
                    .category(category)
                    .build());
        }

        // FollowKeywords — replace all
        followKeywordRepository.deleteByAccountId(account.getId());
        if (req.keywords() != null) {
            for (OnboardingRequest.KeywordEntry ke : req.keywords()) {
                followKeywordRepository.save(FollowKeyword.builder()
                        .account(account)
                        .keyword(ke.keyword())
                        .type(ke.type())
                        .build());
            }
        }

        // ReadingPreference — upsert
        ReadingPreference rp = readingPreferenceRepository.findByAccountId(account.getId())
                .orElseGet(() -> ReadingPreference.builder().account(account)
                        .summaryDepth(req.summaryDepth()).consumeMode(req.consumeMode()).build());
        rp.update(req.summaryDepth(), req.consumeMode());
        readingPreferenceRepository.save(rp);

        // BriefingSettings — upsert
        BriefingSettings existing = briefingSettingsRepository.findByAccountId(account.getId()).orElse(null);
        BriefingSettings bs;
        if (existing != null) {
            existing.update(req.briefingTime(), req.timezoneOffset(), req.voiceEnabled(), req.pushAgreed());
            bs = existing;
        } else {
            bs = BriefingSettings.builder()
                    .account(account)
                    .briefingTime(req.briefingTime())
                    .timezoneOffset(req.timezoneOffset())
                    .voiceEnabled(req.voiceEnabled())
                    .pushAgreed(req.pushAgreed())
                    .build();
        }
        briefingSettingsRepository.save(bs);

        // Mark onboarding complete; personalization = categories >= 3
        account.completeOnboarding(categories.size() >= 3);
        accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getStatus(Account account) {
        return new OnboardingStatusResponse(
                account.isOnboardingCompleted(),
                account.isPersonalizationActive()
        );
    }
}
