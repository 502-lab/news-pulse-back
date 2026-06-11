package com.newscurator.service;

import com.newscurator.domain.*;
import com.newscurator.dto.request.*;
import com.newscurator.dto.response.*;
import com.newscurator.repository.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserInterestsRepository userInterestsRepository;
    private final FollowKeywordRepository followKeywordRepository;
    private final ReadingPreferenceRepository readingPreferenceRepository;
    private final BriefingSettingsRepository briefingSettingsRepository;

    public ProfileService(UserProfileRepository userProfileRepository,
                          UserInterestsRepository userInterestsRepository,
                          FollowKeywordRepository followKeywordRepository,
                          ReadingPreferenceRepository readingPreferenceRepository,
                          BriefingSettingsRepository briefingSettingsRepository) {
        this.userProfileRepository = userProfileRepository;
        this.userInterestsRepository = userInterestsRepository;
        this.followKeywordRepository = followKeywordRepository;
        this.readingPreferenceRepository = readingPreferenceRepository;
        this.briefingSettingsRepository = briefingSettingsRepository;
    }

    // ─── UserProfile ───

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Account account) {
        return userProfileRepository.findByAccountId(account.getId())
                .map(p -> new UserProfileResponse(p.getNickname(), p.getAgeGroup(), p.getOccupation()))
                .orElse(new UserProfileResponse(null, null, null));
    }

    @Transactional
    public UserProfileResponse updateProfile(Account account, UserProfileRequest req) {
        UserProfile profile = userProfileRepository.findByAccountId(account.getId())
                .orElseGet(() -> UserProfile.builder().account(account).build());
        profile.update(req.nickname(), req.ageGroup(), req.occupation());
        userProfileRepository.save(profile);
        return new UserProfileResponse(profile.getNickname(), profile.getAgeGroup(), profile.getOccupation());
    }

    // ─── UserInterests ───

    @Transactional(readOnly = true)
    public UserInterestsResponse getInterests(Account account) {
        List<String> cats = userInterestsRepository.findByAccountId(account.getId())
                .stream().map(UserInterests::getCategory).toList();
        return new UserInterestsResponse(cats);
    }

    @Transactional
    public UserInterestsResponse updateInterests(Account account, UserInterestsRequest req) {
        if (req.categories() == null || req.categories().size() < 3) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "관심 카테고리는 최소 3개 이상이어야 합니다");
        }
        userInterestsRepository.deleteByAccountId(account.getId());
        for (String cat : req.categories()) {
            userInterestsRepository.save(UserInterests.builder().account(account).category(cat).build());
        }
        return new UserInterestsResponse(req.categories());
    }

    // ─── FollowKeywords ───

    @Transactional(readOnly = true)
    public FollowKeywordsResponse getKeywords(Account account) {
        List<FollowKeywordsResponse.KeywordEntry> entries = followKeywordRepository
                .findByAccountId(account.getId())
                .stream()
                .map(fk -> new FollowKeywordsResponse.KeywordEntry(fk.getKeyword(), fk.getType()))
                .toList();
        return new FollowKeywordsResponse(entries);
    }

    @Transactional
    public FollowKeywordsResponse updateKeywords(Account account, FollowKeywordsRequest req) {
        followKeywordRepository.deleteByAccountId(account.getId());
        if (req.keywords() != null) {
            for (FollowKeywordsRequest.KeywordEntry ke : req.keywords()) {
                followKeywordRepository.save(FollowKeyword.builder()
                        .account(account).keyword(ke.keyword()).type(ke.type()).build());
            }
        }
        List<FollowKeywordsResponse.KeywordEntry> result = followKeywordRepository
                .findByAccountId(account.getId())
                .stream()
                .map(fk -> new FollowKeywordsResponse.KeywordEntry(fk.getKeyword(), fk.getType()))
                .toList();
        return new FollowKeywordsResponse(result);
    }

    // ─── ReadingPreference ───

    @Transactional(readOnly = true)
    public ReadingPreferenceResponse getReadingPreference(Account account) {
        return readingPreferenceRepository.findByAccountId(account.getId())
                .map(rp -> new ReadingPreferenceResponse(rp.getSummaryDepth(), rp.getConsumeMode()))
                .orElse(new ReadingPreferenceResponse(null, null));
    }

    @Transactional
    public ReadingPreferenceResponse updateReadingPreference(Account account, ReadingPreferenceRequest req) {
        ReadingPreference rp = readingPreferenceRepository.findByAccountId(account.getId())
                .orElseGet(() -> ReadingPreference.builder().account(account)
                        .summaryDepth(req.summaryDepth()).consumeMode(req.consumeMode()).build());
        rp.update(req.summaryDepth(), req.consumeMode());
        readingPreferenceRepository.save(rp);
        return new ReadingPreferenceResponse(rp.getSummaryDepth(), rp.getConsumeMode());
    }

    // ─── BriefingSettings ───

    @Transactional(readOnly = true)
    public BriefingSettingsResponse getBriefingSettings(Account account) {
        return briefingSettingsRepository.findByAccountId(account.getId())
                .map(bs -> new BriefingSettingsResponse(bs.getBriefingTime(), bs.getTimezoneOffset(),
                        bs.isVoiceEnabled(), bs.isPushAgreed(), bs.getPushAgreedAt()))
                .orElse(new BriefingSettingsResponse(null, null, false, false, null));
    }

    @Transactional
    public BriefingSettingsResponse updateBriefingSettings(Account account, BriefingSettingsRequest req) {
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
        return new BriefingSettingsResponse(bs.getBriefingTime(), bs.getTimezoneOffset(),
                bs.isVoiceEnabled(), bs.isPushAgreed(), bs.getPushAgreedAt());
    }
}
