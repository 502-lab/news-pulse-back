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
     * OAuth 콜백 처리. state 검증 → 토큰 교환 → 계정 조회/생성.
     * 신규 소셜 가입: 201 isNew=true, emailVerified=true (FR-024). consents 필수.
     * 기존 소셜 로그인: 200 isNew=false. consents/ageConfirmed 무시.
     * 동일 이메일 이메일 계정 존재: 409 (FR-007).
     */
    @Transactional
    public Map<String, Object> callback(SocialProvider provider, String code, String state,
                                        String redirectUri, String userJson,
                                        List<ConsentInput> consents, Boolean ageConfirmed) {
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
            return Map.of("tokens", tokens, "account", summary, "isNew", false);
        }

        // New social user: check email conflict
        if (userInfo.email() != null) {
            Optional<Account> existingByEmail =
                    accountRepository.findByEmailIgnoreCase(userInfo.email());
            if (existingByEmail.isPresent()) {
                throw new SocialEmailConflictException(
                        "An account with this email already exists. Please log in with email.");
            }
        }

        // Validate required consents before creating account
        validateNewUserConsents(consents, ageConfirmed);

        Account newAccount = Account.builder()
                .email(userInfo.email())
                .passwordHash(null)
                .role(AccountRole.USER)
                .status(AccountStatus.ACTIVE)
                .signupType(SignupType.SOCIAL)
                .emailVerified(true)  // FR-024: social provider verified
                .build();
        accountRepository.save(newAccount);

        // For Apple, rawUserInfo from the token exchange is null;
        // the userJson form field sent from the client (first login only) is stored instead.
        String storedUserInfo = userInfo.rawUserInfo() != null ? userInfo.rawUserInfo() : userJson;

        SocialConnection connection = SocialConnection.builder()
                .account(newAccount)
                .provider(provider)
                .providerUserId(userInfo.providerUserId())
                .providerEmail(userInfo.email())
                .userInfo(storedUserInfo)
                .build();
        socialConnectionRepository.save(connection);

        saveConsents(newAccount, consents);

        TokenPairResponse tokens = tokenService.issueTokenPair(newAccount);
        AccountSummaryResponse summary = authService.buildAccountSummary(newAccount);
        return Map.of("tokens", tokens, "account", summary, "isNew", true);
    }

    private void validateRedirectUri(String redirectUri) {
        List<String> allowed = oauthConfig.getAllowedRedirectUris();
        if (!allowed.contains(redirectUri)) {
            throw new OAuthStateInvalidException(
                    "Redirect URI not allowed: " + redirectUri);
        }
    }

    private void validateNewUserConsents(List<ConsentInput> consents, Boolean ageConfirmed) {
        if (Boolean.TRUE.equals(ageConfirmed) == false) {
            throw new IllegalArgumentException("만 14세 이상 동의가 필요합니다");
        }
        if (consents == null || consents.isEmpty()) {
            throw new IllegalArgumentException("필수 약관 동의가 필요합니다");
        }
        List<TermsVersion> requiredTerms = termsVersionRepository.findByIsActiveTrueAndIsRequiredTrue();
        for (TermsVersion required : requiredTerms) {
            boolean agreed = consents.stream()
                    .anyMatch(c -> c.termsVersionId().equals(required.getId()) && Boolean.TRUE.equals(c.agreed()));
            if (!agreed) {
                throw new IllegalArgumentException("필수 약관 '" + required.getType() + "' 동의가 필요합니다");
            }
        }
    }

    private void saveConsents(Account account, List<ConsentInput> consents) {
        if (consents == null) return;
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
