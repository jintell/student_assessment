# GitHub Copilot Instructions — Student Assessment Platform - Vertical Slices in Modular Monolith
## Purpose

This repository is a modular monolith.

Implementation work is tracked locally in tasks.md files under the tasks/ directory.

Each task uses:

- `[ ]` — Open / not completed
- `[*]` — Completed by the developer

GitHub Issues are used to track ownership, implementation progress, review, and delivery.

The local tasks.md tracker and GitHub Issues must remain synchronized.

## 1. Task Source of Truth

All implementation tasks originate from tasks.md files.

Example:

```yaml
tasks/
├── foundation/
│   ├── baseline/
│   │   └── tasks.md
│   ├── configuration/
│   │   └── tasks.md
│   └── security/
│       └── tasks.md
├── users/
│   └── tasks.md
├── payments/
│   └── tasks.md
└── orders/
└── tasks.md
```

When asked to generate GitHub Issues from tasks, scan the requested tasks.md file or directory.

## 2. Task Status Rules

Interpret task markers as follows:

- `[ ]`   = Open
- `[*]`   = Completed


Only tasks marked [ ] should normally become GitHub Issues.

Never create a new GitHub Issue for a task already marked [*].

Do not change [ ] to [*] automatically when generating or updating GitHub Issues.

The developer is responsible for changing the local task marker from:

- `[ ]` Task description


to:

- `[*]` Task description


when the implementation is complete.

## 3. Creating GitHub Issues

When asked to create GitHub Issues from a tasks.md file:

1. Read the specified tasks.md. 
2. Identify every task marked [ ]. 
3. Ignore tasks marked [*]. 
4. Check existing GitHub Issues before creating new ones. 
5. Do not create duplicate Issues for tasks that are already tracked. 
6. Create one GitHub Issue for each distinct implementation task. 
7. Preserve the original task wording where practical. 
8. Include the source tasks.md path in every Issue.

### Issue title

Use:
```yaml
[<Module>][<Component>] <Task>
```

Example:
```yaml
[Foundation][Baseline] Create application configuration
```

### Issue body

Use this structure:
```yaml
## Task

<original task from tasks.md>

## Module

Foundation

## Component

Baseline

## Source

`tasks/foundation/baseline/tasks.md`

## Acceptance Criteria

- [ ] Implementation completed
- [ ] Relevant tests added or updated
- [ ] Existing tests continue to pass
- [ ] Code follows project conventions
- [ ] Code is ready for review

## Developer

<assigned developer>

## Completion Workflow

Before creating the PR, the developer must update the source
`tasks.md` task from `[ ]` to `[*]`.

The PR must reference this Issue.
```

## 4. Assigning Developers

When creating or organizing Issues, assign the task to the appropriate developer when ownership is known.

Use repository context, existing assignments, module ownership, CODEOWNERS, or explicit PM instructions when determining ownership.

Do not invent a developer assignment.

If ownership cannot be determined, leave the Issue unassigned and clearly report it.

## 5. Labels

Use consistent labels.

Recommended labels:
```yaml
module:foundation
module:users
module:payments
module:orders

component:baseline
component:configuration
component:security

type:feature
type:bug
type:technical

status:ready
status:in-progress
status:blocked
```

Do not create unnecessary labels.

If the required label does not exist, report that it needs to be created rather than silently inventing a different label.

## 6. Developer Implementation Workflow

When a developer is assigned an Issue, the expected workflow is:
```yaml
GitHub Issue
↓
Developer starts work
↓
Implement task
↓
Add/update tests
↓
Verify implementation
↓
Update tasks.md
↓
[ ] → [*]
↓
Create Pull Request
↓
Reference GitHub Issue
↓
Code Review
↓
Merge
```

The developer must update the local tasks.md tracker before creating the PR.

Example:

Before implementation:
```yaml
- [ ] Create application configuration
```

After implementation:
```yaml
- [*] Create application configuration
```

## 7. Important: Do Not Mark Tasks Complete Prematurely

Do not change a task from:
```yaml
[ ]
```

to:
```yaml
[*]
```

merely because:

- a GitHub Issue was created
- a developer was assigned
- implementation started
- a branch was created
- a PR was opened

The task should become [*] only after the developer has completed the implementation and verified the acceptance criteria.

The developer performs this update locally before creating the PR.

## 8. Pull Requests

Every implementation PR should reference the corresponding GitHub Issue.

Preferred format:
```yaml
Closes #123
```

or:
```yaml
Fixes #123
```

The PR should contain:
```yaml
## Summary

<short description>

## Changes

- <change 1>
- <change 2>

## Tests

- <test information>

## Task Tracker

Updated:

`tasks/foundation/baseline/tasks.md`

Task changed from:

`[ ]`

to:

`[*]`

## Related Issue

Closes #123
```
## 9. Do Not Modify tasks.md During Issue Generation

When generating GitHub Issues from tasks.md, do not modify the source file.

For example, if the file contains:
```yaml
- [ ] Create application configuration
- [*] Setup logging
- [ ] Add health check
```

Issue generation must leave the file exactly as it is.

The result should be Issues for:
```yaml
Create application configuration
Add health check
```

and no Issue for:
```yaml
Setup logging
```

## 10. Duplicate Detection

Before creating an Issue, search existing Issues for the same task.

Consider an Issue a possible duplicate when it has:

- the same task
- the same source tasks.md
- the same module/component
- or substantially the same implementation objective

Do not create duplicate Issues.

If a matching Issue already exists, report:
```yaml
Task: Create application configuration
Existing Issue: #123
Action: Skipped — already tracked
```

### 11. Bulk Issue Generation

When asked:
```yaml
Create GitHub Issues from tasks/foundation/baseline/tasks.md
```

perform the following:

### Step 1

Read:
```yaml
tasks/foundation/baseline/tasks.md
```
### Step 2

Extract all:
```yaml
- [ ] ...
```

tasks.

### Step 3

Ignore:
```yaml
- [*] ...
```

tasks.

### Step 4

Check existing GitHub Issues.

### Step 5

Prepare the Issues.

### Step 6

If the user has requested confirmation before creation, show a preview:
```yaml
Proposed Issues

1. [Foundation][Baseline] Create application configuration
2. [Foundation][Baseline] Add health check
3. [Foundation][Baseline] Add integration tests

Skipped

- Setup logging — already marked [*]
- Configure database — existing Issue #120
```
### Step 7

After confirmation, create the Issues.

### Step 8

Return a summary:
```yaml
Created:
- #121 [Foundation][Baseline] Create application configuration
- #122 [Foundation][Baseline] Add health check
- #123 [Foundation][Baseline] Add integration tests

Skipped:
- Setup logging — completed [*]
- Configure database — existing Issue #120
```
### 12. Handling Completed Tasks

A completed task is represented by:
```yaml
- [*] Task
```

This means the developer has completed the implementation and updated the local tracker.

Do not create another Issue for that task unless explicitly requested.

If an existing GitHub Issue corresponds to the completed task, it should normally be associated with the implementation PR and closed through the normal GitHub workflow.

### 13. Handling Blocked Tasks

If a developer cannot complete a task because of an external dependency, the task should remain:
```yaml
- [ ] Task
```

The corresponding GitHub Issue should be marked:
```yaml
status:blocked
```

and the Issue should explain the blocker.

Do not change a blocked task to:
```yaml
[*]
```

until the implementation is actually complete.

### 14. Task Granularity

Create separate Issues when tasks represent independently deliverable pieces of work.

For example:
```yaml
- [ ] Create User entity
- [ ] Create User repository
- [ ] Implement User service
- [ ] Add User API
```

should normally produce four Issues.

However, do not split a task into artificial Issues when the tasks.md entry clearly represents one atomic implementation.

### 15. Module Context

The directory path provides module context.

For example:
```yaml
tasks/foundation/baseline/tasks.md
```

means:
```yaml
Module: Foundation
Component: Baseline
```

Therefore:
```yaml
- [ ] Create base configuration
```

becomes:
```yaml
[Foundation][Baseline] Create base configuration
```

For:
```yaml
tasks/payments/processing/tasks.md
```

use:
```yaml
[Payments][Processing] <task>
```

### 16. Keep GitHub and tasks.md Synchronized

The intended relationship is:
```yaml
tasks.md
│
│ [ ] task
↓
GitHub Issue
│
│ assigned
↓
Developer
│
│ implementation
↓
tasks.md
│
│ [*] task
↓
Pull Request
│
│ Closes #Issue
↓
GitHub Issue closed
```

GitHub Issues provide team-level visibility.

`tasks.md` provides the module-level implementation checklist.

Both should remain consistent.

### 17. Never Assume Completion From GitHub Status Alone

Do not infer that a task is completed solely because:

- the Issue is closed
- the PR is merged
- the developer says it is done
- the branch was deleted

The local tracker should explicitly reflect completion:
```yaml
[*]
```

The expected developer workflow is to update tasks.md before creating the PR.

### 18. Default Behavior

Unless the PM explicitly requests otherwise:

Read tasks.md before creating Issues.
- Treat [ ] as open.
- Treat [*] as completed.
- Create one Issue per open implementation task.
- Check for duplicate Issues.
- Include the source task path.
- Include module/component information.
- Assign developers only when ownership is known.
- Do not modify tasks.md during Issue generation.
- Developers update [ ] → [*] before creating their PR.
- PRs must reference the corresponding Issue.
- Completed Issues should be closed through the PR workflow.

The goal is a simple and traceable flow:
```yaml
Open task
↓
GitHub Issue
↓
Developer assigned
↓
Implementation
↓
Developer changes [ ] → [*]
↓
PR
↓
Review
↓
Merge
↓
Issue closed
```