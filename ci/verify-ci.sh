#!/usr/bin/env bash
# verify-ci.sh
#
# Runs the exact `compile` job from .github/workflows/ci.yml locally, so a
# stub gap is caught before pushing instead of by a red tag build.
#
# The CI matrix is replicated in full:
#   - target 2024x compiled with --release 17
#   - target 2026x compiled with --release 21
# STUBS_RELEASE is what CI passes to prepare-ci-libs.sh, and it is also the
# --release the stubs are built for, so it must match the JDK per leg.
#
# Note: CI compiles the Java layer only. `gradle compileJava` does not compile
# the Groovy tool scripts, so a broken .groovy file will not be caught here.
#
# Usage:
#   ./ci/verify-ci.sh
#
# Exit code 0 means every leg passed; non-zero means at least one failed.
set -uo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$PWD"

# Keep in sync with the matrix in .github/workflows/ci.yml.
MATRIX=(
  "2024x 17"
  "2026x 21"
)

FAILED=()

for leg in "${MATRIX[@]}"; do
  read -r target release <<< "$leg"
  echo "==================================================="
  echo "  CI leg: target=$target release=$release"
  echo "==================================================="

  STUBS_RELEASE="$release" bash ci/prepare-ci-libs.sh > /tmp/verify-ci-prep-$target.log 2>&1
  if [ $? -ne 0 ]; then
    echo "FAIL  prepare-ci-libs.sh (see /tmp/verify-ci-prep-$target.log)"
    tail -20 /tmp/verify-ci-prep-$target.log
    FAILED+=("$target: prepare")
    continue
  fi

  # 'clean' is deliberate: without it Gradle can report success from a cached
  # UP-TO-DATE result without ever touching the stub classpath.
  gradle clean compileJava \
    -Ptarget="$target" \
    -PcameoHome="$REPO_ROOT/ci-libs" \
    --no-daemon --console=plain > /tmp/verify-ci-compile-$target.log 2>&1
  if [ $? -ne 0 ]; then
    echo "FAIL  compileJava (see /tmp/verify-ci-compile-$target.log)"
    grep -E 'error:' /tmp/verify-ci-compile-$target.log | head -20
    FAILED+=("$target: compile")
    continue
  fi

  # A cached pass would hide a stub regression, so refuse to accept one.
  if grep -q 'UP-TO-DATE' /tmp/verify-ci-compile-$target.log; then
    echo "FAIL  compileJava did not actually run (UP-TO-DATE)"
    FAILED+=("$target: up-to-date")
    continue
  fi

  if grep -qE 'error:' /tmp/verify-ci-compile-$target.log; then
    echo "FAIL  compileJava reported errors but exited 0"
    grep -E 'error:' /tmp/verify-ci-compile-$target.log | head -20
    FAILED+=("$target: silent errors")
    continue
  fi

  echo "PASS  target=$target"
done

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "CI parity: all ${#MATRIX[@]} legs passed"
  exit 0
fi

echo "CI parity FAILED:"
printf '  - %s\n' "${FAILED[@]}"
exit 1
