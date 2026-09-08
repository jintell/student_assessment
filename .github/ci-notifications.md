# CI Engineering Notifications

The `engineering-notification` check and its pull-request/commit annotation are the engineering notification
channel for blocking architecture failures. This keeps the notification on the affected change and requires
no third-party webhook, repository secret, or write-capable workflow token.

The notification job runs when either `stage-4a` or `arch-conformance` fails. Its check annotation exposes the
stage, failing rule or Stage 4a step, and first-failure reason directly on the change; its summary includes the
same data plus the commit, ref, and workflow-run link. Engineers can see the blocking reason from the checks
surface without opening the failed gate's log.

Stage 4a preserves its first-failure semantics. Architecture conformance reports the first R1-R8 diagnostic;
failures before a rule diagnostic use an explicit stage-level fallback. The notification job receives only
these bounded outputs and inherits the workflow's read-only permissions.
