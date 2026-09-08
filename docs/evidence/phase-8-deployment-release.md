# Phase 8 Deployment and Release Evidence

## P8.1 Reproducible Build

- Verified on: 2026-09-07
- Git commit: `baa3f825836ad481a17d8e5e4429a4bf54c6aa97`
- Command, executed twice: `./gradlew clean bootJar --no-build-cache --rerun-tasks --console=plain`
- Artifact: `build/libs/cbt-platform-0.0.1-SNAPSHOT.jar`
- First build SHA-256: `4636f1bda76a517794c1bc2e4ca48e9dc357c247c6a8cf4901c89084914ff32a`
- Second build SHA-256: `4636f1bda76a517794c1bc2e4ca48e9dc357c247c6a8cf4901c89084914ff32a`
- First and second size: `58,593,368` bytes

The tracked worktree was clean before the run. Both builds succeeded from the same commit with the Gradle
build cache disabled, and their byte-for-byte artifact digests and sizes matched.

## P8.2 Single-Artifact Packaging Review

- `settings.gradle.kts` declares only the `cbt-platform` root project and includes no subprojects.
- `bootJar` produces one executable artifact: `build/libs/cbt-platform-0.0.1-SNAPSHOT.jar`.
- The manifest selects `org.meldtech.platform.CbtPlatformApplication` as the application entry point.
- The executable contains all twelve context-module boundary classes beneath `BOOT-INF/classes/org/meldtech/platform`.
- There are no module-specific JARs or module-specific deployment descriptors.

The module boundaries are packaging-internal boundaries within one deployable, preserving `ARC-PLAT-001`.

## P8.3 Main Branch Protection Verification

On 2026-09-07, the GitHub branch-protection API initially reported no legacy protection for `main`; the only
repository ruleset targeted `feature*`. The reviewed policy in `.github/branch-protection/main.json` was then
applied and independently read back through the API.

The live `main` policy now has:

- strict required checks: `build`, `static-analysis`, `stage-4a`, `arch-conformance`, `unit-tests`,
  `slice-tests`, and `docs-conformance`;
- required signed commits;
- one approving pull-request review with stale-review dismissal;
- enforcement for administrators; and
- force pushes and branch deletion disabled.

Because administrators are included and the force-push capability is disabled, an administrator force push
is not a bypass path. The live check names match both the checked-in policy and `.github/workflows/ci.yml`.

## P8.5 Deployment Deferral Register

These capabilities are deliberately outside `FEAT-PLAT-001`; absence here is not evidence that they are
implemented.

| Deferred capability | Owner | Closure evidence |
|---|---|---|
| Image signing and provenance | `FEAT-OPS-007` | Deployment pipeline rejects an unsigned image and retains provenance. |
| SBOM attachment to the released image | `FEAT-OPS-007` | The single image has one attached, retained software bill of materials. |
| Immutable digest tagging | `FEAT-OPS-007` | Deployment manifests and release records select an immutable image digest. |
| `api`, `worker`, and `pindist` runtime profiles | `FEAT-PLAT-006` | One image starts in each profile with the required bean, route, identity, and scheduler isolation. |
| Migration-first rolling deployment and service-level-gated canary sequence | `FEAT-OPS-007` | Canary and post-deployment failure rehearsals trigger bounded automatic rollback. |

The current foundation artifact remains a single deployable and does not anticipate these features with
partial profile or deployment implementations.
