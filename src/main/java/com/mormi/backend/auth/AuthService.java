package com.mormi.backend.auth;

import com.mormi.backend.auth.AuthDtos.EducatorResponse;
import com.mormi.backend.auth.AuthDtos.EducatorSignupRequest;
import com.mormi.backend.auth.AuthDtos.LoginRequest;
import com.mormi.backend.auth.AuthDtos.LoginResponse;
import com.mormi.backend.auth.AuthDtos.SignupRequest;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.learner.ConsentRecordService;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import com.mormi.backend.learner.LearnerRepository;
import com.mormi.backend.organization.CohortResearchCodeRepository;
import com.mormi.backend.organization.Educator;
import com.mormi.backend.organization.EducatorRepository;
import com.mormi.backend.organization.LearnerEnrollment;
import com.mormi.backend.organization.LearnerEnrollmentRepository;
import com.mormi.backend.organization.Organization;
import com.mormi.backend.organization.OrganizationRepository;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 인증. 학생·교사가 accounts 한 테이블을 쓰므로 로그인은 하나고 가입만 갈린다.
 * 토큰은 auth_tokens 에 세션 단위로 쌓이므로 기기마다 따로 살아 있고,
 * 로그아웃은 해당 행만, 전체 로그아웃은 계정의 모든 행을 폐기한다.
 */
@Service
public class AuthService {

    /** 아이디가 있는지 없는지를 응답으로 구분할 수 없게 로그인 실패는 한 문구만 쓴다. */
    private static final String LOGIN_FAILED = "아이디 또는 비밀번호가 올바르지 않습니다.";

    private final AccountRepository accountRepository;
    private final AuthTokenRepository authTokenRepository;
    private final LearnerRepository learnerRepository;
    private final EducatorRepository educatorRepository;
    private final OrganizationRepository organizationRepository;
    private final CohortResearchCodeRepository cohortResearchCodeRepository;
    private final LearnerEnrollmentRepository learnerEnrollmentRepository;
    private final ConsentRecordService consentRecordService;
    private final TokenHasher tokenHasher;
    private final PasswordEncoder passwordEncoder;
    private final RewardService rewardService;

    public AuthService(
            AccountRepository accountRepository,
            AuthTokenRepository authTokenRepository,
            LearnerRepository learnerRepository,
            EducatorRepository educatorRepository,
            OrganizationRepository organizationRepository,
            CohortResearchCodeRepository cohortResearchCodeRepository,
            LearnerEnrollmentRepository learnerEnrollmentRepository,
            TokenHasher tokenHasher,
            PasswordEncoder passwordEncoder,
            RewardService rewardService,
            ConsentRecordService consentRecordService) {
        this.accountRepository = accountRepository;
        this.authTokenRepository = authTokenRepository;
        this.learnerRepository = learnerRepository;
        this.educatorRepository = educatorRepository;
        this.organizationRepository = organizationRepository;
        this.cohortResearchCodeRepository = cohortResearchCodeRepository;
        this.learnerEnrollmentRepository = learnerEnrollmentRepository;
        this.tokenHasher = tokenHasher;
        this.passwordEncoder = passwordEncoder;
        this.rewardService = rewardService;
        this.consentRecordService = consentRecordService;
    }

    @Transactional
    public LearnerResponse signup(SignupRequest request) {
        String loginId = request.loginId().trim();
        String researchCode = request.researchCode().trim();
        if (accountRepository.existsByLoginId(loginId)) {
            throw ApiException.conflict("login_id_taken", "이미 사용 중인 아이디입니다.");
        }
        if (learnerRepository.existsByResearchCode(researchCode)) {
            throw ApiException.conflict("research_code_taken", "이미 등록된 연구 코드입니다.");
        }

        Account account = accountRepository.save(Account.register(
                loginId, passwordEncoder.encode(request.password()), Account.ROLE_LEARNER));
        Learner learner = learnerRepository.save(Learner.register(
                request.displayName().trim(), researchCode, account.getId()));
        // 지갑 시작 잔액을 원장 첫 줄로 남긴다. 학습자당 한 번만 적립된다.
        rewardService.grant(
                learner.getId(), null, RewardSource.SEED, CurriculumCatalog.WALLET_SEED,
                "seed:" + learner.getId());

        // 교사가 사전 발급한 참여 번호면 그 학급에 자동 재적된다.
        // 발급 장부에 없는 번호는 무소속으로 남고, 나중에 교사가 발급하면 소급 재적된다.
        String collectedBy = cohortResearchCodeRepository.findByCode(researchCode)
                .map(issued -> {
                    learnerEnrollmentRepository.save(
                            LearnerEnrollment.of(learner.getId(), issued.getCohortId()));
                    return educatorRepository.findById(issued.getIssuedBy())
                            .map(Educator::getDisplayName).orElse(null);
                })
                .orElse(null);
        // 동의는 참여 번호를 전달한 교사·연구자가 보호자에게 받아온 것으로 본다.
        consentRecordService.recordBaseline(
                learner.getId(), learner.isConversationStorageConsent(), collectedBy);

        return LearnerResponse.of(learner, issueToken(account.getId()));
    }

    /**
     * 교사 가입. 기관은 이름이 정확히 같으면 같은 기관으로 합류하고, 없으면 새로 만든다.
     * 파일럿 기관 수가 적어 이름 충돌보다 "같은 학교 교사가 따로 노는" 쪽이 더 큰 문제다.
     */
    @Transactional
    public EducatorResponse educatorSignup(EducatorSignupRequest request) {
        String loginId = request.loginId().trim();
        if (accountRepository.existsByLoginId(loginId)) {
            throw ApiException.conflict("login_id_taken", "이미 사용 중인 아이디입니다.");
        }
        String organizationName = request.organizationName().trim();
        Organization organization = organizationRepository.findByName(organizationName)
                .orElseGet(() -> organizationRepository.save(Organization.of(organizationName)));

        Account account = accountRepository.save(Account.register(
                loginId, passwordEncoder.encode(request.password()), Account.ROLE_EDUCATOR));
        Educator educator = educatorRepository.save(Educator.withAccount(
                organization.getId(), request.displayName().trim(), request.position(),
                account.getId()));

        return EducatorResponse.of(educator, organization, issueToken(account.getId()));
    }

    /**
     * 통합 로그인. 응답의 role 로 프런트가 학생 홈·교사 화면으로 도착지를 가른다.
     * 아이디가 없을 때와 비밀번호가 틀릴 때의 응답이 같아야 가입 여부가 새지 않는다.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        Account account = accountRepository.findByLoginId(request.loginId().trim())
                .orElseThrow(() -> ApiException.unauthorized(LOGIN_FAILED));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw ApiException.unauthorized(LOGIN_FAILED);
        }
        String token = issueToken(account.getId());
        if (account.isLearner()) {
            Learner learner = learnerRepository.findByAccountId(account.getId())
                    .orElseThrow(() -> ApiException.unauthorized(LOGIN_FAILED));
            return LoginResponse.forLearner(LearnerResponse.of(learner, null), token);
        }
        Educator educator = educatorRepository.findByAccountId(account.getId())
                .orElseThrow(() -> ApiException.unauthorized(LOGIN_FAILED));
        Organization organization = organizationRepository.findById(educator.getOrganizationId())
                .orElseThrow(() -> ApiException.unauthorized(LOGIN_FAILED));
        return LoginResponse.forEducator(EducatorResponse.of(educator, organization, null), token);
    }

    /**
     * 매 요청 인증 경로. 조회·유효성 검사·슬라이딩 갱신을 한 트랜잭션에서 처리한다.
     * 만료·폐기된 토큰은 비어 있는 결과가 되어 SecurityConfig 가 401 로 막는다.
     */
    @Transactional
    public Optional<AccountPrincipal> authenticate(String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return authTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .filter(token -> token.isValid(now))
                .flatMap(token -> {
                    token.extendIfStale(now);
                    return accountRepository.findById(token.getAccountId())
                            .flatMap(account -> subjectId(account)
                                    .map(subjectId -> new AccountPrincipal(
                                            account.getId(), account.getRole(),
                                            subjectId, token.getId())));
                });
    }

    /** 역할별 프로필의 PK. 계정만 있고 프로필 행이 없는 비정상 데이터는 인증을 거부한다. */
    private Optional<Long> subjectId(Account account) {
        if (account.isLearner()) {
            return learnerRepository.findByAccountId(account.getId()).map(Learner::getId);
        }
        return educatorRepository.findByAccountId(account.getId()).map(Educator::getId);
    }

    /** 현재 기기만 로그아웃. 다른 기기의 토큰은 건드리지 않는다. */
    @Transactional
    public void logout(Long tokenId) {
        authTokenRepository.findById(tokenId)
                .ifPresent(token -> token.revoke(OffsetDateTime.now()));
    }

    /** 공용 기기에 로그인한 채로 두고 온 경우를 위해 계정의 모든 토큰을 폐기한다. */
    @Transactional
    public void logoutAll(Long accountId) {
        authTokenRepository.revokeAllByAccountId(accountId, OffsetDateTime.now());
    }

    /** 새 세션 발급. 평문 토큰은 이 반환값으로만 밖에 나가고 저장되지 않는다. */
    @Transactional
    public String issueToken(Long accountId) {
        String token = tokenHasher.newToken();
        authTokenRepository.save(
                AuthToken.issue(accountId, tokenHasher.hash(token), OffsetDateTime.now()));
        return token;
    }
}
