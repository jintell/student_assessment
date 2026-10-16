# Shared Runtime Image

Run `./gradlew runtimeImage` from the repository root. The standard
`./gradlew bootBuildImage` entry point delegates to the same task; its original
buildpack action is skipped so it cannot create a second image variant.
The build uses the one `bootJar`, one Dockerfile, one linux/amd64 target and one
final-image SPDX SBOM. API, worker and pindist are not build arguments, build
targets or separate JARs. All use the same digest; only
`SPRING_PROFILES_ACTIVE` selects the runtime role. Bean/route isolation and
invalid-profile rejection are implemented and exercised in Phase 4/P7.10.

Prerequisites: JDK 21, Docker with Buildx, Bash, jq and shasum. The script creates
the dedicated `cbt-runtime` docker-container builder when absent, without
changing the active builder. This supports attestations even when Docker's
local image store does not. The builder/cache can be removed after use with
`docker buildx rm cbt-runtime`. The base Java 21 image reuses the rehearsal
baseline's immutable digest; the SBOM scanner is also digest-pinned.

The build context contains only the shared executable JAR and Dockerfile.
No repository files, environment credentials or profile overrides are copied.
The runtime is UID/GID 10001 and retains the shell utilities needed by preStop.
The script does not forward shell environment variables as Docker build args.

Generated artifacts under `../../build/runtime-image` (not committed):

- `oci/`: OCI layout containing one runnable manifest and one SBOM attestation.
- `metadata.json`: BuildKit's digest metadata.
- `sbom.spdx.json`: the single attached SPDX document, extracted without rescanning.
- `image.json`: verified index digest, runtime manifest digest, SBOM hash and the
  same image digest for all three roles.

The OCI index digest is the release reference; the runnable manifest digest is
the attestation subject. They intentionally differ. The verifier checks the
index, manifest, config and statement hashes, exactly one runtime image and one
SBOM, subject linkage, a non-root Java entry point, no baked profile/JVM override,
and that the SBOM includes packaged Spring Boot dependencies. Six negative
fixtures cover variant images, missing/duplicate SBOMs, profile baking,
subject mismatch and tampered metadata. `./gradlew runtimeImageContractTest`
runs these checks without Docker.

[Docker's SBOM attestation documentation](https://docs.docker.com/build/metadata/attestations/sbom/)
describes the SPDX/in-toto attachment format used here. Publishing must preserve
the OCI index and attestation, then set the single `CBT_PLATFORM_IMAGE_DIGEST`
deployment input to `repository@<imageDigest>` for every role. This task does not
push an image, sign it, introduce CI stage 14, or attest a deployed environment;
release signing, registry retention and deployment proof remain P8.3/FEAT-OPS-007.
Do not use `docker load` into a legacy image store as a release path: that can
discard attestations. No role-specific rebuild is permitted during promotion.
