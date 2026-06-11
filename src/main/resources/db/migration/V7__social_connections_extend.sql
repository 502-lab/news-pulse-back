-- V7: social_connections에 provider_email, user_info 컬럼 추가
ALTER TABLE social_connections ADD COLUMN provider_email VARCHAR(255);
ALTER TABLE social_connections ADD COLUMN user_info TEXT;
