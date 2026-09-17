# Main Branch Protection

`main.json` is the reviewed source of truth for the `main` branch protection policy. Repository administrators
apply it through the GitHub branch-protection API or their organization policy controller.

The `requiredSignatures` field maps to GitHub's separate required-commit-signatures endpoint. The remaining
fields map to the branch protection update endpoint. Any remote policy drift must be reconciled back to this
file through review; CI check names must continue to match `.github/workflows/ci.yml` exactly.

`stage-12-migration` is a required blocking check. It runs after the delivered
predecessor verification jobs and must succeed before `docs-conformance`
(CI stage 13) can run.
