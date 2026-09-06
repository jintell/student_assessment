# Security Hardening Review

## Deny-by-default route coverage

Status: PASS (2026-09-05)

`RoutePolicyCoverageTests` imports production classes from the main output and requires every
`PolicyProtectedRoute` package to contain exactly one `SlicePolicy`. Its isolated
`MissingPolicyRoute` fixture deliberately omits a policy and is rejected with:

```text
has 0 policies; exactly one is required
```

Evidence command:

```bash
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.conformance.RoutePolicyCoverageTests'
```

The production assertion and deliberate-omission assertion both pass. Policy coverage is therefore a
blocking Stage 4 conformance condition, independent of runtime configuration.

## Secret-scanning baseline

Status: PASS (2026-09-05)

`ci/secret-scan` downloads Gitleaks 8.30.1 from its official release, verifies the archive against a
platform-specific SHA-256 digest, and then performs two scans:

1. Every commit reachable through `git --all`.
2. The current working tree, including untracked source files.

The default Gitleaks rules are extended only to exclude generated/tool directories, committed public keys,
and detached signatures. No credential, token, private key, or other secret was reported in either scan.
The redacted reports and summary are retained in `build/reports/secret-scan`.

The development PostgreSQL service uses trust authentication only on an ephemeral loopback-bound port, so
no database credential is stored in `compose.yaml` or exposed beyond the developer machine.

## Configuration and secret resolution

Status: PASS (2026-09-05)

The committed `application.yaml` contains only the application name, logging format, and an optional
`configtree:/run/secrets/` import. A production secret manager must mount secret values into that directory;
Spring external configuration resolves them at runtime. No secret-bearing placeholder has a committed
fallback.

The build has no secret input. The application was started to Netty readiness with an empty environment
except for `HOME`, `PATH`, and `JAVA_HOME`; no secret directory was present. Flyway and R2DBC obtained the
local development connection from Spring Boot's Docker Compose service connection. The local database is
ephemeral, trust-authenticated, and bound to a dynamically allocated loopback port, so this convenience
cannot become a remotely reachable production default.

## Non-disclosing denial response

Status: PASS (2026-09-05)

`SliceTest.endpointReturnsANonDisclosingPolicyDenial` requires HTTP 403, problem JSON, and `no-store`. It
also requires the response object to contain exactly `status`, `code`, and `correlationId`. Extra fields fail
the assertion, so stack traces, exception messages, SQL, policy predicates, and required authorities cannot
be added without breaking Stage 5 and Stage 7.

## Stage 4a bypass review

Status: PASS (2026-09-05)

`ci/test-stage-4a-bypass` records and refuses these downgrade attempts:

| Attempt | Required result | Observed result |
|---|---|---|
| `--skip` | Reject | Exit 2, unknown argument |
| `--advisory` | Reject | Exit 2, unknown argument |
| `--warn-only` | Reject | Exit 2, unknown argument |
| `STAGE4A_SKIP=true` | Remain blocking | BLOCKED, exit 1 |
| `STAGE4A_ADVISORY=true` | Remain blocking | BLOCKED, exit 1 |
| `STAGE4A_WARN_ONLY=true` | Remain blocking | BLOCKED, exit 1 |

The same executable rejects workflow `continue-on-error` or an ignored Stage 4a exit and verifies the
ordered `needs` path `stage-4a` -> `arch-conformance` -> `unit-tests` -> `slice-tests` ->
`docs-conformance`. The detailed retained result is `build/reports/stage-4a/bypass-review.txt`.

## Temporary architecture gate

Status: PASS, NOT IN FORCE (2026-09-05)

The ratified `P0.5` path is active and `architecture-ratification.json` contains no
`temporaryArchitectureGate`. The alternative `P0.6` path remains explicitly not applicable.

The Stage 4a unit harness nevertheless proves the dormant path is fail-closed:

| Fixture | Result |
|---|---|
| `implementationAllowed: true` | FAIL at Step 3 |
| `implementationAllowed: false`, expired `allowedUntil` | BLOCKED at Step 3 with expiry date |
| `implementationAllowed: false`, unexpired `allowedUntil` | BLOCKED at Step 3 as preparatory-only |

The expiry comparison uses the current UTC date and has no renewal path. A new exception would require a
new committed artifact with both named role approvals; it still cannot authorize implementation.

## CI supply-chain review

Status: PASS (2026-09-05)

`ci/verify-workflow-security` enforces the reviewed action lock in `config/ci-actions.lock`:

| Action | Pinned commit | Reviewed tag |
|---|---|---|
| `actions/checkout` | `d23441a48e516b6c34aea4fa41551a30e30af803` | `v6` |
| `actions/setup-java` | `b6effb05e454b25005698d916606bdc6ffcbf961` | `v5` |
| `gradle/actions/setup-gradle` | `9c971963bec38e04b3d30dcc455b5382be2fdbfb` | `v6` |
| `actions/upload-artifact` | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | `v7` |

The workflow grants only `contents: read`; every unspecified `GITHUB_TOKEN` permission is `none`. All
checkouts set `persist-credentials: false`. `pull_request_target` is forbidden.

The private architecture baseline needs a fine-grained, read-only repository token. Stage 4a is guarded at
job level with `github.event_name != 'pull_request'`, so that token is never provided to a pull-request
runner. Because every subsequent job uses the ordered `needs` chain, those privileged and post-ratification
stages are skipped together on pull requests and run only for trusted pushes or explicitly called workflows.
The token has no `github.token` fallback; absence fails the trusted run closed.

The machine-readable review output is retained at `build/reports/security/workflow-review.txt`.
