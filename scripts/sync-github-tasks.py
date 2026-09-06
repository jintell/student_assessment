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

Requirements:
    - GitHub CLI (`gh`) installed.
    - `gh` authenticated.
    - In GitHub Actions:
        permissions:
          contents: read
          issues: write

Example:
    python scripts/sync-github-tasks.py \
        --tasks tasks/foundation/baseline/tasks.md
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

FEATURE_PATTERN = re.compile(r"`?(FEAT-[A-Z0-9-]+)`?")
PHASE_PATTERN = re.compile(r"^#\s+Phase\s+(\d+)\b", re.IGNORECASE)

# Examples:
#   13. [ ] Assert stage 4a ...
#   13. [*] Assert stage 4a ...
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
        Repository-wide stable key.

        P7.13 alone is not guaranteed to be unique because another feature
        could also contain P7.13.
        """
        return f"{self.feature_id}:{self.task_id}"

    @property
    def title(self) -> str:
        return f"[{self.feature_id}][{self.task_id}] {clean_title(self.text)}"


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
# GitHub CLI
# ---------------------------------------------------------------------------

def run_gh(*args: str, capture: bool = True) -> str:
    command = ["gh", *args]

    try:
        result = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.PIPE,
        )
        return result.stdout.strip() if capture else ""

    except FileNotFoundError:
        fail(
            "GitHub CLI (`gh`) was not found. "
            "Install it before running this script."
        )

    except subprocess.CalledProcessError as exc:
        stderr = exc.stderr.strip() if exc.stderr else ""
        fail(
            f"GitHub command failed:\n"
            f"  {' '.join(command)}\n"
            f"{stderr}"
        )

    raise AssertionError("unreachable")


def resolve_repository(explicit_repo: str | None) -> str:
    """
    Resolve OWNER/REPO.

    Priority:
      1. --repo
      2. GITHUB_REPOSITORY
      3. current gh repository
    """

    if explicit_repo:
        return explicit_repo

    github_repository = os.environ.get("GITHUB_REPOSITORY")
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


def load_github_issues(repo: str) -> dict[str, Issue]:
    """
    Load existing issues once and index synchronized issues by task-key.

    Pull requests are not returned by `gh issue list`.

    A single preload is preferable to making one GitHub search request
    for every task.
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

    records = json.loads(raw or "[]")

    issues: dict[str, Issue] = {}

    for record in records:
        body = record.get("body") or ""

        match = TASK_ID_MARKER_PATTERN.search(body)
        if not match:
            continue

        task_key = match.group("key").strip()

        issue = Issue(
            number=int(record["number"]),
            state=record["state"],
            title=record["title"],
            body=body,
        )

        if task_key in issues:
            fail(
                f"Duplicate GitHub issues found for task-key "
                f"{task_key!r}: #{issues[task_key].number} "
                f"and #{issue.number}."
            )

        issues[task_key] = issue

    return issues


# ---------------------------------------------------------------------------
# Markdown parsing
# ---------------------------------------------------------------------------

def parse_tasks(tasks_path: Path) -> tuple[str, list[Task]]:
    content = tasks_path.read_text(encoding="utf-8")
    lines = content.splitlines()

    feature_id = extract_feature_id(lines)

    if feature_id is None:
        fail(
            f"Could not determine FEAT-* identifier from {tasks_path}."
        )

    current_phase: int | None = None
    tasks: list[Task] = []

    source = repository_relative_path(tasks_path)

    for line_number, line in enumerate(lines, start=1):
        phase_match = PHASE_PATTERN.match(line)

        if phase_match:
            current_phase = int(phase_match.group(1))
            continue

        task_match = TASK_PATTERN.match(line)

        if not task_match:
            continue

        if current_phase is None:
            fail(
                f"Task found before a Phase heading at "
                f"{tasks_path}:{line_number}"
            )

        number = int(task_match.group(1))
        marker = task_match.group("marker")
        text = task_match.group("text").strip()

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

    validate_unique_tasks(tasks)

    return feature_id, tasks


def extract_feature_id(lines: list[str]) -> str | None:
    """
    The current file starts with a heading containing FEAT-PLAT-001.
    Use the first FEAT-* identifier found before the first Phase.
    """

    for line in lines:
        if PHASE_PATTERN.match(line):
            break

        match = FEATURE_PATTERN.search(line)
        if match:
            return match.group(1)

    return None


def validate_unique_tasks(tasks: list[Task]) -> None:
    seen: set[str] = set()

    for task in tasks:
        if task.task_key in seen:
            fail(
                f"Duplicate task identifier found in tasks.md: "
                f"{task.task_key}"
            )

        seen.add(task.task_key)


# ---------------------------------------------------------------------------
# Issue rendering
# ---------------------------------------------------------------------------

def clean_title(text: str, max_length: int = 180) -> str:
    """
    Produce a readable GitHub issue title from the task text.

    Markdown emphasis/backticks are removed only from the title.
    The complete original task remains in the body.
    """

    cleaned = re.sub(r"[`*_]", "", text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()

    # Avoid overly large GitHub issue titles.
    if len(cleaned) > max_length:
        cleaned = cleaned[: max_length - 1].rstrip() + "…"

    return cleaned


def managed_body(task: Task) -> str:
    status = "COMPLETE" if task.completed else "OPEN"
    marker = "[*]" if task.completed else "[ ]"

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


def merge_managed_body(existing_body: str, task: Task) -> str:
    """
    Replace only the machine-managed section.

    Anything written manually outside the managed section is preserved.
    """

    generated = managed_body(task)

    start = existing_body.find(MANAGED_START)
    end = existing_body.find(MANAGED_END)

    if start == -1 or end == -1:
        # Existing sync issue without managed boundaries.
        # Preserve its original body after the generated section.
        if existing_body.strip():
            return (
                generated
                + "\n\n"
                + "<!-- task-sync:preserved-content -->\n"
                + existing_body.strip()
            )

        return generated

    end += len(MANAGED_END)

    before = existing_body[:start].rstrip()
    after = existing_body[end:].lstrip()

    parts = []

    if before:
        parts.append(before)

    parts.append(generated)

    if after:
        parts.append(after)

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Synchronization
# ---------------------------------------------------------------------------

def create_issue(
    repo: str,
    task: Task,
    dry_run: bool,
) -> Issue:
    print(f"CREATE  {task.task_key}")

    if dry_run:
        return Issue(
            number=-1,
            state="CLOSED" if task.completed else "OPEN",
            title=task.title,
            body=managed_body(task),
        )

    number_raw = run_gh(
        "issue",
        "create",
        "--repo",
        repo,
        "--title",
        task.title,
        "--body",
        managed_body(task),
        "--json",
        "number",
        "--jq",
        ".number",
    )

    issue = Issue(
        number=int(number_raw),
        state="OPEN",
        title=task.title,
        body=managed_body(task),
    )

    return issue


def update_issue_metadata(
    repo: str,
    issue: Issue,
    task: Task,
    dry_run: bool,
) -> None:
    desired_body = merge_managed_body(issue.body, task)

    title_changed = issue.title != task.title
    body_changed = issue.body != desired_body

    if not title_changed and not body_changed:
        return

    print(f"UPDATE  {task.task_key} -> #{issue.number}")

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
        args.extend(["--title", task.title])

    if body_changed:
        args.extend(["--body", desired_body])

    run_gh(*args)

    issue.title = task.title
    issue.body = desired_body


def reconcile_issue_state(
    repo: str,
    issue: Issue,
    task: Task,
    dry_run: bool,
) -> None:
    """
    tasks.md is authoritative.

    [ ] -> ensure issue is OPEN
    [*] -> ensure issue is CLOSED with reason completed
    """

    if task.completed:
        if not issue.is_open:
            print(f"OK      {task.task_key} already completed")
            return

        print(f"CLOSE   {task.task_key} -> #{issue.number}")

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

    # An open task in tasks.md must have an open GitHub issue.
    if issue.is_open:
        print(f"OK      {task.task_key} already open")
        return

    print(f"REOPEN  {task.task_key} -> #{issue.number}")

    if not dry_run:
        run_gh(
            "issue",
            "reopen",
            str(issue.number),
            "--repo",
            repo,
        )

    issue.state = "OPEN"


def synchronize(
    repo: str,
    tasks: list[Task],
    issues: dict[str, Issue],
    dry_run: bool,
) -> None:
    created = 0
    updated = 0
    closed = 0
    reopened = 0
    unchanged = 0

    for task in tasks:
        issue = issues.get(task.task_key)

        if issue is None:
            issue = create_issue(repo, task, dry_run)
            issues[task.task_key] = issue
            created += 1

            # Required flow:
            #
            # No issue
            #   -> create issue
            #   -> inspect marker
            #   -> [*] closes newly created issue
            #
            if task.completed and issue.is_open:
                reconcile_issue_state(repo, issue, task, dry_run)
                closed += 1

            continue

        previous_title = issue.title
        previous_body = issue.body
        previous_state = issue.state

        update_issue_metadata(repo, issue, task, dry_run)

        if issue.title != previous_title or issue.body != previous_body:
            updated += 1

        reconcile_issue_state(repo, issue, task, dry_run)

        if previous_state == "OPEN" and issue.state == "CLOSED":
            closed += 1
        elif previous_state == "CLOSED" and issue.state == "OPEN":
            reopened += 1
        elif (
            issue.title == previous_title
            and issue.body == previous_body
            and issue.state == previous_state
        ):
            unchanged += 1

    print()
    print("Synchronization complete")
    print("------------------------")
    print(f"Repository : {repo}")
    print(f"Tasks      : {len(tasks)}")
    print(f"Created    : {created}")
    print(f"Updated    : {updated}")
    print(f"Closed     : {closed}")
    print(f"Reopened   : {reopened}")
    print(f"Unchanged  : {unchanged}")

    if dry_run:
        print("Mode       : DRY RUN")


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def repository_relative_path(path: Path) -> str:
    try:
        root = run_gh(
            "repo",
            "view",
            "--json",
            "nameWithOwner",
            "--jq",
            ".nameWithOwner",
        )

        # `root` above validates that we are inside a GitHub repository,
        # but Git is the reliable way to find the filesystem root.
        del root

        git_root = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout.strip()

        return path.resolve().relative_to(Path(git_root).resolve()).as_posix()

    except (subprocess.CalledProcessError, ValueError):
        return path.as_posix()


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


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
        help="Path to tasks.md (default: tasks.md)",
    )

    parser.add_argument(
        "--repo",
        help=(
            "GitHub repository as OWNER/REPO. "
            "Defaults to GITHUB_REPOSITORY or the current gh repository."
        ),
    )

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show intended actions without modifying GitHub.",
    )

    return parser.parse_args()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = parse_args()

    tasks_path = args.tasks

    if not tasks_path.is_file():
        fail(f"Task file does not exist: {tasks_path}")

    repo = resolve_repository(args.repo)

    feature_id, tasks = parse_tasks(tasks_path)

    if not tasks:
        fail(f"No tasks found in {tasks_path}")

    print(f"Feature    : {feature_id}")
    print(f"Task file  : {tasks_path}")
    print(f"Repository : {repo}")
    print(f"Tasks      : {len(tasks)}")

    if args.dry_run:
        print("Mode       : DRY RUN")

    print()

    issues = load_github_issues(repo)

    synchronize(
        repo=repo,
        tasks=tasks,
        issues=issues,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()