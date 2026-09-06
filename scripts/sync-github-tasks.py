#!/usr/bin/env python3
"""
Synchronize tasks.md with GitHub Issues.

Source of truth:
    tasks.md

Rules:
    [ ] -> GitHub issue MUST be open
    [*] -> GitHub issue MUST be closed as completed

Behaviour:
    - Creates an issue when no matching issue exists.
    - Reuses the existing issue on later runs.
    - Reopens an issue if tasks.md says [ ] but the issue is closed.
    - Closes an issue if tasks.md says [*] but the issue is open.
    - Updates the managed issue title/body when task text changes.
    - Never modifies tasks.md.
    - Safe to run repeatedly (idempotent).
    - Only numbered checklist items inside "# Phase N ..." sections
      are interpreted as tasks.
    - Numbered checklists under appendices or other H1 sections
      are ignored.

Requirements:
    - GitHub CLI (`gh`) installed.
    - `gh` authenticated.
    - In GitHub Actions:

        permissions:
          contents: read
          issues: write

Example:

    python scripts/sync-github-tasks.py \
        --tasks tasks/foundation/baseline/tasks.md \
        --repo OWNER/REPOSITORY

Dry run:

    python scripts/sync-github-tasks.py \
        --tasks tasks/foundation/baseline/tasks.md \
        --repo OWNER/REPOSITORY \
        --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

FEATURE_PATTERN = re.compile(
    r"`?(FEAT-[A-Z0-9-]+)`?"
)

PHASE_PATTERN = re.compile(
    r"^#\s+Phase\s+(\d+)\b",
    re.IGNORECASE,
)

# Any H1 heading, for example:
#
#   # Appendix A
#   # Appendix B
#   # Definition of Done
#
# A non-Phase H1 terminates the current phase scope.
TOP_LEVEL_HEADING_PATTERN = re.compile(
    r"^#\s+"
)

# Examples:
#
#   13. [ ] Assert stage 4a ...
#   13. [*] Assert stage 4a ...
#
TASK_PATTERN = re.compile(
    r"^\s*(\d+)\.\s+\[(?P<marker>[ *])\]\s+(?P<text>.+?)\s*$"
)

TASK_ID_MARKER_PATTERN = re.compile(
    r"<!--\s*task-key:(?P<key>[^>]+?)\s*-->"
)

MANAGED_START = "<!-- task-sync:managed:start -->"
MANAGED_END = "<!-- task-sync:managed:end -->"


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Task:
    feature_id: str
    phase: int
    number: int
    completed: bool
    text: str
    source: str

    @property
    def task_id(self) -> str:
        return f"P{self.phase}.{self.number}"

    @property
    def task_key(self) -> str:
        """
        Repository-wide stable task key.

        P7.13 alone is not globally unique because another feature may
        also contain P7.13.

        Therefore:

            FEAT-PLAT-001:P7.13

        is used as the persistent synchronization key.
        """
        return f"{self.feature_id}:{self.task_id}"

    @property
    def title(self) -> str:
        return (
            f"[{self.feature_id}]"
            f"[{self.task_id}] "
            f"{clean_title(self.text)}"
        )


@dataclass
class Issue:
    number: int
    state: str
    title: str
    body: str

    @property
    def is_open(self) -> bool:
        return self.state.upper() == "OPEN"


# ---------------------------------------------------------------------------
# Error handling
# ---------------------------------------------------------------------------

def fail(message: str) -> None:
    print(
        f"ERROR: {message}",
        file=sys.stderr,
    )
    raise SystemExit(1)


# ---------------------------------------------------------------------------
# GitHub CLI
# ---------------------------------------------------------------------------

def run_gh(
    *args: str,
    capture: bool = True,
) -> str:
    command = ["gh", *args]

    try:
        result = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=(
                subprocess.PIPE
                if capture
                else None
            ),
            stderr=subprocess.PIPE,
        )

        if not capture:
            return ""

        return result.stdout.strip()

    except FileNotFoundError:
        fail(
            "GitHub CLI (`gh`) was not found. "
            "Install it before running this script."
        )

    except subprocess.CalledProcessError as exc:
        stderr = (
            exc.stderr.strip()
            if exc.stderr
            else ""
        )

        fail(
            "GitHub command failed:\n"
            f"  {' '.join(command)}\n"
            f"{stderr}"
        )

    raise AssertionError("unreachable")


def resolve_repository(
    explicit_repo: str | None,
) -> str:
    """
    Resolve OWNER/REPO.

    Priority:

      1. --repo
      2. GITHUB_REPOSITORY
      3. current repository resolved by gh
    """

    if explicit_repo:
        return explicit_repo

    github_repository = os.environ.get(
        "GITHUB_REPOSITORY"
    )

    if github_repository:
        return github_repository

    return run_gh(
        "repo",
        "view",
        "--json",
        "nameWithOwner",
        "--jq",
        ".nameWithOwner",
    )


def load_github_issues(
    repo: str,
) -> dict[str, Issue]:
    """
    Load synchronized GitHub Issues once and index them by task-key.

    Example task-key:

        FEAT-PLAT-001:P7.13

    Only issues containing the managed task-key marker are considered.

    Pull requests are not returned by `gh issue list`.
    """

    raw = run_gh(
        "issue",
        "list",
        "--repo",
        repo,
        "--state",
        "all",
        "--limit",
        "1000",
        "--json",
        "number,state,title,body",
    )

    records = json.loads(
        raw or "[]"
    )

    issues: dict[str, Issue] = {}

    for record in records:
        body = record.get("body") or ""

        match = TASK_ID_MARKER_PATTERN.search(
            body
        )

        if not match:
            continue

        task_key = (
            match
            .group("key")
            .strip()
        )

        issue = Issue(
            number=int(record["number"]),
            state=record["state"],
            title=record["title"],
            body=body,
        )

        if task_key in issues:
            previous = issues[task_key]

            fail(
                "Duplicate GitHub issues found for "
                f"task-key {task_key!r}: "
                f"#{previous.number} and #{issue.number}."
            )

        issues[task_key] = issue

    return issues


# ---------------------------------------------------------------------------
# Markdown parsing
# ---------------------------------------------------------------------------

def extract_feature_id(
    lines: list[str],
) -> str | None:
    """
    Extract the first FEAT-* identifier before the first Phase heading.

    Example:

        # Task List — `FEAT-PLAT-001`
    """

    for line in lines:
        if PHASE_PATTERN.match(line):
            break

        match = FEATURE_PATTERN.search(
            line
        )

        if match:
            return match.group(1)

    return None


def parse_tasks(
    tasks_path: Path,
) -> tuple[str, list[Task]]:
    """
    Parse numbered checklist tasks that appear inside:

        # Phase N ...

    Any other H1 heading terminates the current phase.

    This prevents numbered checklist items under appendices,
    Definition of Done sections, etc. from being interpreted as
    phase tasks.
    """

    content = tasks_path.read_text(
        encoding="utf-8"
    )

    lines = content.splitlines()

    feature_id = extract_feature_id(
        lines
    )

    if feature_id is None:
        fail(
            "Could not determine FEAT-* identifier "
            f"from {tasks_path}."
        )

    current_phase: int | None = None
    tasks: list[Task] = []

    source = repository_relative_path(
        tasks_path
    )

    for line_number, line in enumerate(
        lines,
        start=1,
    ):
        # ---------------------------------------------------------------
        # Enter a Phase section
        # ---------------------------------------------------------------

        phase_match = PHASE_PATTERN.match(
            line
        )

        if phase_match:
            current_phase = int(
                phase_match.group(1)
            )
            continue

        # ---------------------------------------------------------------
        # Leave the Phase when another H1 section starts
        #
        # Example:
        #
        #   # Appendix A
        #
        # Without this reset, Appendix checklist entries would continue
        # being parsed as Phase 10 tasks.
        # ---------------------------------------------------------------

        if TOP_LEVEL_HEADING_PATTERN.match(
            line
        ):
            current_phase = None
            continue

        # ---------------------------------------------------------------
        # Detect a numbered task/checklist
        # ---------------------------------------------------------------

        task_match = TASK_PATTERN.match(
            line
        )

        if not task_match:
            continue

        # Checklist entries outside a Phase are not operational tasks.
        if current_phase is None:
            continue

        number = int(
            task_match.group(1)
        )

        marker = task_match.group(
            "marker"
        )

        text = (
            task_match
            .group("text")
            .strip()
        )

        tasks.append(
            Task(
                feature_id=feature_id,
                phase=current_phase,
                number=number,
                completed=marker == "*",
                text=text,
                source=source,
            )
        )

    validate_unique_tasks(
        tasks
    )

    return feature_id, tasks


def validate_unique_tasks(
    tasks: list[Task],
) -> None:
    """
    Fail immediately if tasks.md contains duplicate task identities.

    We intentionally do not silently choose one task because that would
    break deterministic synchronization.
    """

    seen: set[str] = set()

    for task in tasks:
        if task.task_key in seen:
            fail(
                "Duplicate task identifier found "
                f"in tasks.md: {task.task_key}"
            )

        seen.add(
            task.task_key
        )


# ---------------------------------------------------------------------------
# Issue rendering
# ---------------------------------------------------------------------------

def clean_title(
    text: str,
    max_length: int = 180,
) -> str:
    """
    Produce a readable GitHub issue title.

    Markdown emphasis/backticks are removed from the title only.
    The complete original task remains in the issue body.
    """

    cleaned = re.sub(
        r"[`*_]",
        "",
        text,
    )

    cleaned = re.sub(
        r"\s+",
        " ",
        cleaned,
    ).strip()

    if len(cleaned) > max_length:
        cleaned = (
            cleaned[
                : max_length - 1
            ].rstrip()
            + "…"
        )

    return cleaned


def managed_body(
    task: Task,
) -> str:
    status = (
        "COMPLETE"
        if task.completed
        else "OPEN"
    )

    marker = (
        "[*]"
        if task.completed
        else "[ ]"
    )

    return f"""\
{MANAGED_START}
<!-- task-sync:v1 -->
<!-- task-key:{task.task_key} -->
<!-- task-id:{task.task_id} -->
<!-- feature-id:{task.feature_id} -->

## Source task

**Feature:** `{task.feature_id}`
**Task:** `{task.task_id}`
**Status:** `{status}`
**Marker:** `{marker}`
**Source:** `{task.source}`

## Task

{task.text}

## Synchronization

This issue is managed from `tasks.md`.

- `[ ]` → issue must be open
- `[*]` → issue must be closed as completed

GitHub issue state is not authoritative and must not be used to update
`tasks.md`.

{MANAGED_END}"""


def merge_managed_body(
    existing_body: str,
    task: Task,
) -> str:
    """
    Replace only the machine-managed section.

    Any human-written issue content outside the managed block is kept.
    """

    generated = managed_body(
        task
    )

    start = existing_body.find(
        MANAGED_START
    )

    end = existing_body.find(
        MANAGED_END
    )

    # Existing synchronized issue from an older script version without
    # managed boundaries.
    if start == -1 or end == -1:
        if existing_body.strip():
            return (
                generated
                + "\n\n"
                + "<!-- task-sync:preserved-content -->\n"
                + existing_body.strip()
            )

        return generated

    end += len(
        MANAGED_END
    )

    before = (
        existing_body[:start]
        .rstrip()
    )

    after = (
        existing_body[end:]
        .lstrip()
    )

    parts: list[str] = []

    if before:
        parts.append(
            before
        )

    parts.append(
        generated
    )

    if after:
        parts.append(
            after
        )

    return "\n\n".join(
        parts
    )


# ---------------------------------------------------------------------------
# GitHub issue operations
# ---------------------------------------------------------------------------

def create_issue(
    repo: str,
    task: Task,
    dry_run: bool,
) -> Issue:
    """
    Create exactly one GitHub Issue.

    GitHub creates the issue in OPEN state first. If tasks.md says [*],
    synchronize() subsequently closes it as completed.
    """

    print(
        f"CREATE  {task.task_key}"
    )

    if dry_run:
        return Issue(
            number=-1,
            state="OPEN",
            title=task.title,
            body=managed_body(task),
        )

    issue_url = run_gh(
        "issue",
        "create",
        "--repo",
        repo,
        "--title",
        task.title,
        "--body",
        managed_body(task),
    )

    # Typical output:
    #
    # https://github.com/OWNER/REPO/issues/123
    #
    match = re.search(
        r"/issues/(\d+)/?$",
        issue_url,
    )

    if not match:
        fail(
            "Could not determine issue number "
            "from GitHub response: "
            f"{issue_url}"
        )

    issue_number = int(
        match.group(1)
    )

    return Issue(
        number=issue_number,
        state="OPEN",
        title=task.title,
        body=managed_body(task),
    )


def update_issue_metadata(
    repo: str,
    issue: Issue,
    task: Task,
    dry_run: bool,
) -> None:
    desired_body = merge_managed_body(
        issue.body,
        task,
    )

    title_changed = (
        issue.title
        != task.title
    )

    body_changed = (
        issue.body
        != desired_body
    )

    if (
        not title_changed
        and not body_changed
    ):
        return

    print(
        f"UPDATE  {task.task_key} "
        f"-> #{issue.number}"
    )

    if dry_run:
        issue.title = task.title
        issue.body = desired_body
        return

    args = [
        "issue",
        "edit",
        str(issue.number),
        "--repo",
        repo,
    ]

    if title_changed:
        args.extend(
            [
                "--title",
                task.title,
            ]
        )

    if body_changed:
        args.extend(
            [
                "--body",
                desired_body,
            ]
        )

    run_gh(
        *args
    )

    issue.title = task.title
    issue.body = desired_body


def reconcile_issue_state(
    repo: str,
    issue: Issue,
    task: Task,
    dry_run: bool,
) -> None:
    """
    Reconcile GitHub to tasks.md.

    tasks.md is authoritative.

        [ ] -> issue MUST be OPEN
        [*] -> issue MUST be CLOSED as completed
    """

    # ------------------------------------------------------------------
    # Completed task
    # ------------------------------------------------------------------

    if task.completed:
        if not issue.is_open:
            print(
                f"OK      {task.task_key} "
                "already completed"
            )
            return

        print(
            f"CLOSE   {task.task_key} "
            f"-> #{issue.number}"
        )

        if not dry_run:
            run_gh(
                "issue",
                "close",
                str(issue.number),
                "--repo",
                repo,
                "--reason",
                "completed",
            )

        issue.state = "CLOSED"
        return

    # ------------------------------------------------------------------
    # Open task
    # ------------------------------------------------------------------

    if issue.is_open:
        print(
            f"OK      {task.task_key} "
            "already open"
        )
        return

    # tasks.md says [ ], therefore a manually closed issue must be
    # reopened so GitHub continues to mirror the authoritative source.
    print(
        f"REOPEN  {task.task_key} "
        f"-> #{issue.number}"
    )

    if not dry_run:
        run_gh(
            "issue",
            "reopen",
            str(issue.number),
            "--repo",
            repo,
        )

    issue.state = "OPEN"


# ---------------------------------------------------------------------------
# Synchronization
# ---------------------------------------------------------------------------

def synchronize(
    repo: str,
    tasks: list[Task],
    issues: dict[str, Issue],
    dry_run: bool,
) -> None:
    """
    Idempotently synchronize all parsed tasks.

    Truth table:

        tasks.md   GitHub       Action
        ------------------------------------------
        [ ]        missing      CREATE OPEN
        [ ]        OPEN         NO STATE CHANGE
        [ ]        CLOSED       REOPEN

        [*]        missing      CREATE + CLOSE
        [*]        OPEN         CLOSE completed
        [*]        CLOSED       NO STATE CHANGE
    """

    created = 0
    updated = 0
    closed = 0
    reopened = 0
    unchanged = 0

    for task in tasks:
        issue = issues.get(
            task.task_key
        )

        # ---------------------------------------------------------------
        # Issue does not exist
        # ---------------------------------------------------------------

        if issue is None:
            issue = create_issue(
                repo,
                task,
                dry_run,
            )

            issues[
                task.task_key
            ] = issue

            created += 1

            # A completed task still follows the required flow:
            #
            # create issue
            #    ↓
            # inspect marker
            #    ↓
            # [*] → close completed
            #
            if (
                task.completed
                and issue.is_open
            ):
                reconcile_issue_state(
                    repo,
                    issue,
                    task,
                    dry_run,
                )

                closed += 1

            continue

        # ---------------------------------------------------------------
        # Existing issue
        # ---------------------------------------------------------------

        previous_title = (
            issue.title
        )

        previous_body = (
            issue.body
        )

        previous_state = (
            issue.state
        )

        # Keep title/body synchronized with tasks.md.
        update_issue_metadata(
            repo,
            issue,
            task,
            dry_run,
        )

        metadata_changed = (
            issue.title
            != previous_title
            or issue.body
            != previous_body
        )

        if metadata_changed:
            updated += 1

        # Keep issue state synchronized with tasks.md.
        reconcile_issue_state(
            repo,
            issue,
            task,
            dry_run,
        )

        if (
            previous_state == "OPEN"
            and issue.state == "CLOSED"
        ):
            closed += 1

        elif (
            previous_state == "CLOSED"
            and issue.state == "OPEN"
        ):
            reopened += 1

        elif (
            not metadata_changed
            and issue.state
            == previous_state
        ):
            unchanged += 1

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------

    print()
    print(
        "Synchronization complete"
    )
    print(
        "------------------------"
    )
    print(
        f"Repository : {repo}"
    )
    print(
        f"Tasks      : {len(tasks)}"
    )
    print(
        f"Created    : {created}"
    )
    print(
        f"Updated    : {updated}"
    )
    print(
        f"Closed     : {closed}"
    )
    print(
        f"Reopened   : {reopened}"
    )
    print(
        f"Unchanged  : {unchanged}"
    )

    if dry_run:
        print(
            "Mode       : DRY RUN"
        )


# ---------------------------------------------------------------------------
# Repository utilities
# ---------------------------------------------------------------------------

def repository_relative_path(
    path: Path,
) -> str:
    """
    Return path relative to the Git repository root when possible.
    """

    try:
        result = subprocess.run(
            [
                "git",
                "rev-parse",
                "--show-toplevel",
            ],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        git_root = Path(
            result.stdout.strip()
        ).resolve()

        return (
            path.resolve()
            .relative_to(git_root)
            .as_posix()
        )

    except (
        subprocess.CalledProcessError,
        ValueError,
    ):
        return path.as_posix()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Synchronize tasks.md to GitHub Issues. "
            "tasks.md is the authoritative source."
        )
    )

    parser.add_argument(
        "--tasks",
        type=Path,
        default=Path("tasks.md"),
        help=(
            "Path to tasks.md "
            "(default: tasks.md)"
        ),
    )

    parser.add_argument(
        "--repo",
        help=(
            "GitHub repository as OWNER/REPO. "
            "Defaults to GITHUB_REPOSITORY "
            "or the current gh repository."
        ),
    )

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Show intended actions "
            "without modifying GitHub."
        ),
    )

    return parser.parse_args()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = parse_args()

    tasks_path = args.tasks

    if not tasks_path.is_file():
        fail(
            "Task file does not exist: "
            f"{tasks_path}"
        )

    repo = resolve_repository(
        args.repo
    )

    feature_id, tasks = parse_tasks(
        tasks_path
    )

    if not tasks:
        fail(
            "No Phase tasks found in "
            f"{tasks_path}"
        )

    print(
        f"Feature    : {feature_id}"
    )
    print(
        f"Task file  : {tasks_path}"
    )
    print(
        f"Repository : {repo}"
    )
    print(
        f"Tasks      : {len(tasks)}"
    )

    if args.dry_run:
        print(
            "Mode       : DRY RUN"
        )

    print()

    issues = load_github_issues(
        repo
    )

    synchronize(
        repo=repo,
        tasks=tasks,
        issues=issues,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()