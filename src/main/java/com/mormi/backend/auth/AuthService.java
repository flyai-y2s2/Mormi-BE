package com.mormi.backend.auth;

import com.mormi.backend.auth.AuthDtos.LoginRequest;
import com.mormi.backend.auth.AuthDtos.SignupRequest;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import com.mormi.backend.learner.LearnerRepository;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학습자 인증. 토큰은 learner_tokens 에 세션 단위로 쌓이므로 기기마다 따로 살아 있고,
 * 로그아웃은 해당 행만, 전체 로그아웃은 학습자의 모든 행을 폐기한다.
 */
@Service
public class AuthService {

    /** 아이디가 있는지 없는지를 응답으로 구분할 수 없게 로그인 실패는 한 문구만 쓴다. */
    private static final String LOGIN_FAILED = "아이디 또는 비밀번호가 올바르지 않습니다.";

    private final LearnerRepository learnerRepository;
    private final LearnerTokenRepository learnerTokenRepository;
    private final TokenHasher tokenHasher;
    private final PasswordEncoder passwordEncoder;
    private final RewardService rewardService;

    public AuthService(
            LearnerRepository learnerRepository,
            LearnerTokenRepository learnerTokenRepository,
            TokenHasher tokenHasher,
            PasswordEncoder passwordEncoder,
            RewardService rewardService) {
        this.learnerRepository = learnerRepository;
        this.learnerTokenRepository = learnerTokenRepository;
        this.tokenHasher = tokenHasher;
        this.passwordEncoder = passwordEncoder;
        this.rewardService = rewardService;
    }

    @Transactional
    public LearnerResponse signup(SignupRequest request) {
        String loginId = request.loginId().trim();
        String researchCode = request.researchCode().trim();
        if (learnerRepository.existsByLoginId(loginId)) {
            throw ApiException.conflict("login_id_taken", "이미 사용 중인 아이디입니다.");
        }
        if (learnerRepository.existsByResearchCode(researchCode)) {
            throw ApiException.conflict("research_code_taken", "이미 등록된 연구 코드입니다.");
        }

        Learner learner = learnerRepository.save(Learner.register(
                request.displayName().trim(),
                researchCode,
                loginId,
                passwordEncoder.encode(request.password())));
        // 지갑 시작 잔액을 원장 첫 줄로 남긴다. 학습자당 한 번만 적립된다.
        rewardService.grant(
                learner.getId(), null, RewardSource.SEED, CurriculumCatalog.WALLET_SEED,
                "seed:" + learner.getId());

        return LearnerResponse.of(learner, issueToken(learner.getId()));
    }

    /**
     * 로그인. 기존 토큰을 폐기하지 않으므로 태블릿과 보호자 휴대폰이 동시에 살아 있다.
     * 아이디가 없을 때와 비밀번호가 틀릴 때의 응답이 같아야 가입 여부가 새지 않는다.
     */
    @Transactional
    public LearnerResponse login(LoginRequest request) {
        Learner learner = learnerRepository.findByLoginId(request.loginId().trim())
                .orElseThrow(() -> ApiException.unauthorized(LOGIN_FAILED));
        if (learner.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), learner.getPasswordHash())) {
            throw ApiException.unauthorized(LOGIN_FAILED);
        }
        return LearnerResponse.of(learner, issueToken(learner.getId()));
    }

    /**
     * 매 요청 인증 경로. 조회·유효성 검사·슬라이딩 갱신을 한 트랜잭션에서 처리한다.
     * 만료·폐기된 토큰은 비어 있는 결과가 되어 SecurityConfig 가 401 로 막는다.
     */
    @Transactional
    public Optional<LearnerPrincipal> authenticate(String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return learnerTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .filter(token -> token.isValid(now))
                .map(token -> {
                    token.extendIfStale(now);
                    return new LearnerPrincipal(token.getLearnerId(), token.getId());
                });
    }

    /** 현재 기기만 로그아웃. 다른 기기의 토큰은 건드리지 않는다. */
    @Transactional
    public void logout(Long tokenId) {
        learnerTokenRepository.findById(tokenId)
                .ifPresent(token -> token.revoke(OffsetDateTime.now()));
    }

    /** 공용 기기에 로그인한 채로 두고 온 경우를 위해 학습자의 모든 토큰을 폐기한다. */
    @Transactional
    public void logoutAll(Long learnerId) {
        learnerTokenRepository.revokeAllByLearnerId(learnerId, OffsetDateTime.now());
    }

    /**
     * 새 세션 발급. 평문 토큰은 이 반환값으로만 밖에 나가고 저장되지 않는다.
     * deprecated 된 연구 코드 온보딩도 같은 경로로 토큰을 받아야 인증이 유지된다.
     */
    @Transactional
    public String issueToken(Long learnerId) {
        String token = tokenHasher.newToken();
        learnerTokenRepository.save(
                LearnerToken.issue(learnerId, tokenHasher.hash(token), OffsetDateTime.now()));
        return token;
    }
}
