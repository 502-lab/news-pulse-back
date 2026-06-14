#!/usr/bin/env bash
# specs/api-contract/openapi.yaml 를 spec repo에 커밋·푸시하고
# 백엔드 repo의 서브모듈 포인터를 갱신한다.
#
# 사용법:
#   ./scripts/sync-spec.sh
#   ./scripts/sync-spec.sh "feat: 새 API 추가"   <- 커밋 메시지 직접 지정
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC_DIR="$REPO_ROOT/specs"
OPENAPI_FILE="$SPEC_DIR/api-contract/openapi.yaml"

MSG="${1:-"chore: openapi.yaml 업데이트 ($(date +%Y-%m-%d))"}"

echo "==> 변경 확인: $OPENAPI_FILE"
if ! git -C "$SPEC_DIR" diff --quiet "$OPENAPI_FILE" 2>/dev/null && \
   ! git -C "$SPEC_DIR" diff --cached --quiet "$OPENAPI_FILE" 2>/dev/null; then
  :
else
  # untracked 또는 수정됐는지 다시 체크
  STATUS=$(git -C "$SPEC_DIR" status --porcelain "$OPENAPI_FILE" 2>/dev/null || true)
  if [ -z "$STATUS" ]; then
    echo "openapi.yaml에 변경사항이 없습니다. 종료."
    exit 0
  fi
fi

echo "==> spec repo 커밋 중..."
git -C "$SPEC_DIR" add api-contract/openapi.yaml
git -C "$SPEC_DIR" commit -m "$MSG"

echo "==> spec repo 푸시 중..."
git -C "$SPEC_DIR" push origin HEAD

echo "==> 백엔드 repo 서브모듈 포인터 갱신 중..."
git -C "$REPO_ROOT" add specs
git -C "$REPO_ROOT" commit -m "chore(spec): spec submodule 포인터 갱신"

echo ""
echo "완료! 프론트 repo에서 아래 명령어로 최신 spec을 당겨갈 수 있습니다:"
echo "  git submodule update --remote specs"
