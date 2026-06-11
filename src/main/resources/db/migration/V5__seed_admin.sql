-- V5: ADMIN 계정 시드
-- Flyway placeholder: ${admin-email}, ${admin-password-hash}
-- 비밀번호 해시는 BCrypt 형식으로 환경변수를 통해 주입
INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)
VALUES (
    '${admin-email}',
    '${admin-password-hash}',
    'ADMIN',
    'ACTIVE',
    TRUE,
    'EMAIL'
) ON CONFLICT (email) DO NOTHING;
