# ADR-0009: Stub Sync Strategy

## Status

Accepted

## Context

The `ci-libs/` directory contains stubs for proprietary Cameo SDK classes, enabling `compileJava` without a real Cameo installation. When new Java source code references Cameo API classes or methods not yet covered by stubs, compilation fails.

Options to keep stubs in sync:
1. Pre-commit hook running `gradle compileJava -PcameoHome=ci-libs`.
2. Gradle `checkStubs` task that scans source for Cameo API usage and validates stub coverage.
3. CI workflow already runs this check on every tag push (`.github/workflows/ci.yml` compile job).

## Decision

Leave the current approach as-is. CI enforces stub coverage on every tag push. Adding a pre-commit hook or custom Gradle task adds maintenance overhead without improving safety — CI already catches failures before release. The developer (agent or human) updating Java code is responsible for updating stubs when adding new Cameo API calls, which is a low-frequency event.

## Consequences

1. Stub drift is only caught at CI time, not at commit time. Ambiguous but acceptable: stub updates are rare, and CI provides a clear error pointing to the missing class/method.
2. No additional build pipeline complexity.
3. The `prepare-ci-libs.sh` script is the single source of truth for stubs — documented and reproducible.
