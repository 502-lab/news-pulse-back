package com.newscurator.service;

import com.newscurator.client.social.OAuthProviderFactory;
import com.newscurator.client.social.OAuthProviderPort;
import com.newscurator.client.social.OAuthUserInfo;
import com.newscurator.config.OAuthConfig;
import com.newscurator.domain.Account;
import com.newscurator.domain.ConsentRecord;
import com.newscurator.domain.SocialConnection;
import com.newscurator.domain.TermsVersion;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import com.newscurator.domain.enums.SocialProvider;
import com.newscurator.dto.request.ConsentInput;
import com.newscurator.dto.response.AccountSummaryResponse;
import com.newscurator.dto.response.SocialAuthorizeResponse;
import com.newscurator.dto.response.TermsVersionResponse;
import com.newscurator.dto.response.TokenPairResponse;
import com.newscurator.exception.OAuthStateInvalidException;
import com.newscurator.exception.SocialEmailConflictException;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ConsentRecordRepository;
import com.newscurator.repository.SocialConnectionRepository;
import com.newscurator.repository.TermsVersionRepository;
import com.newscurator.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialAuthService {

    private static final Logger log = LoggerFactory.getLogger(SocialAuthService.class);

    private final OAuthProviderFactory providerFactory;
    private final OAuthConfig oauthConfig;
    private final AccountRepository accountRepository;
    private final SocialConnectionRepository socialConnectionRepository;
    private final TermsVersionRepository termsVersionRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final TokenService tokenService;
    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    public SocialAuthService(OAuthProviderFactory providerFactory,
                             OAuthConfig oauthConfig,
                             AccountRepository accountRepository,
                             SocialConnectionRepository socialConnectionRepository,
                             TermsVersionRepository termsVersionRepository,
                             ConsentRecordRepository consentRecordRepository,
                             TokenService tokenService,
                             AuthService authService,
                             JwtTokenProvider jwtTokenProvider) {
        this.providerFactory = providerFactory;
        this.oauthConfig = oauthConfig;
        this.accountRepository = accountRepository;
        this.socialConnectionRepository = socialConnectionRepository;
        this.termsVersionRepository = termsVersionRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.tokenService = tokenService;
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** state JWT가 포함된 provider 인가 URL 반환. redirectUri는 화이트리스트 검증 후 사용. */
    public SocialAuthorizeResponse authorize(SocialProvider provider, String redirectUri) {
        validateRedirectUri(redirectUri);
        String state = jwtTokenProvider.generateOAuthState(provider.name());
        OAuthProviderPort adapter = providerFactory.get(provider);
        return new SocialAuthorizeResponse(adapter.getAuthorizeUrl(state, redirectUri));
    }

    /**
     * OAuth 콜백 처리. state 검증 → 토큰 교환 → 기존/신규 분기.
     * 기존 유저: 200 isNew=false, account+tokens.
     * 신규 유저: 202 isNew=true, pendingToken(10분)+전체 활성 약관 목록. 계정은 생성하지 않음.
     * 이메일 충돌(동일 이메일 이메일 계정 존재): 409.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> callback(SocialProvider provider, String code, String state,
                                        String redirectUri, String userJson) {
        validateState(provider, state);

        OAuthProviderPort adapter = providerFactory.get(provider);
        OAuthUserInfo userInfo = adapter.exchangeAndFetchUser(code, redirectUri);

        Optional<SocialConnection> existingConnection =
                socialConnectionRepository.findByProviderAndProviderUserId(
                        provider, userInfo.providerUserId());

        if (existingConnection.isPresent()) {
            Account account = existingConnection.get().getAccount();
            TokenPairResponse tokens = tokenService.issueTokenPair(account);
            AccountSummaryResponse summary = authService.buildAccountSummary(account);
            return Map.of("isNew", false, "account", summary, "tokens", tokens);
        }

        // Check email conflict before issuing pending token (fail fast)
        if (userInfo.email() != null) {
            Optional<Account> existingByEmail =
                    accountRepository.findByEmailIgnoreCase(userInfo.email());
            if (existingByEmail.isPresent()) {
                throw new SocialEmailConflictException(
                        "An account with this email already exists. Please log in with email.");
            }
        }

        // New user: issue pending token, return terms list. No account created yet.
        String storedUserInfo = userInfo.rawUserInfo() != null ? userInfo.rawUserInfo() : userJson;
        String pendingToken = jwtTokenProvider.generatePendingSignupToken(
                provider.name(), userInfo.providerUserId(), userInfo.email(), storedUserInfo);

        List<TermsVersionResponse> activeTerms = termsVersionRepository.findByIsActiveTrue()
                .stream()
                .map(tv -> new TermsVersionResponse(tv.getId(), tv.getType(), tv.getVersion(),
                        tv.getEffectiveDate(), tv.isRequired(), tv.isActive()))
                .toList();

        return Map.of("isNew", true, "pendingToken", pendingToken, "requiredTerms", activeTerms);
    }

    /**
     * 소셜 신규 가입 완료. pendingToken 검증 → 약관 검증 → 계정+연결+ConsentRecord 생성 → 201 JWT 발급.
     */
    @Transactional
    public Map<String, Object> complete(String pendingToken, List<ConsentInput> consents,
                                        Boolean ageConfirmed) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parsePendingSignupToken(pendingToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new OAuthStateInvalidException("Invalid or expired pending signup token");
        }

        String providerName = claims.get("provider", String.class);
        String providerUserId = claims.get("pid", String.class);
        String email = claims.get("email", String.class);
        String userInfo = claims.get("userInfo", String.class);
        SocialProvider provider = SocialProvider.valueOf(providerName);

        validateNewUserConsents(consents, ageConfirmed);

        // Re-check email conflict (race condition: another signup with same email between callback and complete)
        if (email != null) {
            accountRepository.findByEmailIgnoreCase(email).ifPresent(existing -> {
                throw new SocialEmailConflictException(
                        "An account with this email already exists. Please log in with email.");
            });
        }

        Account newAccount = Account.builder()
                .email(email)
                .passwordHash(null)
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.SOCIAL)
                .emailVerified(true)  // FR-024: social provider verified
                .build();
        accountRepository.save(newAccount);

        SocialConnection connection = SocialConnection.builder()
                .account(newAccount)
                .provider(provider)
                .providerUserId(providerUserId)
                .providerEmail(email)
                .userInfo(userInfo)
                .build();
        socialConnectionRepository.save(connection);

        saveConsents(newAccount, consents);

        TokenPairResponse tokens = tokenService.issueTokenPair(newAccount);
        AccountSummaryResponse summary = authService.buildAccountSummary(newAccount);
        return Map.of("account", summary, "tokens", tokens);
    }

    private void validateRedirectUri(String redirectUri) {
        List<String> allowed = oauthConfig.getAllowedRedirectUris();
        if (!allowed.contains(redirectUri)) {
            throw new OAuthStateInvalidException("Redirect URI not allowed: " + redirectUri);
        }
    }

    private void validateNewUserConsents(List<ConsentInput> consents, Boolean ageConfirmed) {
        if (!Boolean.TRUE.equals(ageConfirmed)) {
            throw new IllegalArgumentException("만 14세 이상 동의가 필요합니다");
        }
        List<TermsVersion> requiredTerms = termsVersionRepository.findByIsActiveTrueAndIsRequiredTrue();
        for (TermsVersion required : requiredTerms) {
            boolean agreed = consents.stream()
                    .anyMatch(c -> c.termsVersionId().equals(required.getId())
                            && Boolean.TRUE.equals(c.agreed()));
            if (!agreed) {
                throw new IllegalArgumentException("필수 약관 '" + required.getType() + "' 동의가 필요합니다");
            }
        }
    }

    private void saveConsents(Account account, List<ConsentInput> consents) {
        for (ConsentInput input : consents) {
            termsVersionRepository.findById(input.termsVersionId()).ifPresent(tv -> {
                ConsentRecord record = ConsentRecord.builder()
                        .account(account)
                        .termsVersion(tv)
                        .agreed(Boolean.TRUE.equals(input.agreed()))
                        .build();
                consentRecordRepository.save(record);
            });
        }
    }

    private void validateState(SocialProvider provider, String state) {
        try {
            Claims claims = jwtTokenProvider.parseOAuthState(state);
            String stateProvider = claims.getSubject();
            if (!provider.name().equals(stateProvider)) {
                throw new OAuthStateInvalidException(
                        "OAuth state provider mismatch: expected " + provider.name()
                        + " but got " + stateProvider);
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw new OAuthStateInvalidException("Invalid or expired OAuth state: " + e.getMessage());
        }
    }
}
