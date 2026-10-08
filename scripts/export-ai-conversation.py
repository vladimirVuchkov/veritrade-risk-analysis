#!/usr/bin/env python3
"""Export a Claude Code session transcript (JSONL) to a readable Markdown record.

Keeps: user messages, visible assistant text, tool calls (tool name + main argument) and tool
results truncated to a few lines. Drops: system reminders, hook output, session start context,
memory content and hidden reasoning. Replaces the home directory with "~" and e-mail addresses
with [REDACTED].

The finished text is checked against a denylist file (one term per line, matched as a whole word
ignoring case, '#' starts a comment) and a set of secret patterns. Any match stops the export with a non-zero
exit code and a report, so the exchange can be reviewed by hand. After review, a matched term that
is personal data can be masked with --redact, and a whole exchange unrelated to the task can be
replaced with a marker with --drop-turn. Wording is never changed otherwise.

Usage:
  scripts/export-ai-conversation.py SESSION.jsonl --denylist PATH --agent orchestrator \\
      --topic "Wave 0 foundations" -o docs/ai-conversations/01-orchestrator-wave-0.md \\
      [--include-subagents] [--redact TERM ...] [--drop-turn N ...] [--result-lines 6]
"""

import argparse
import json
import re
import sys
from pathlib import Path

REDACTED = "[REDACTED]"
UNRELATED_MARKER = "[unrelated exchange removed]"
SYSTEM_BLOCK = re.compile(r"<(system-reminder|local-command-caveat)>.*?</\1>", re.DOTALL)
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
HOME_PATH = re.compile(r"/(?:Users|home)/[^/\s\"'`]+")
SECRET_PATTERNS = {
    "GitHub token": re.compile(r"\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{20,}"),
    "API key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "private key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "bearer token": re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._-]{20,}"),
}
MAIN_ARGUMENT = {
    "Bash": "command",
    "Read": "file_path",
    "Write": "file_path",
    "Edit": "file_path",
    "NotebookEdit": "notebook_path",
    "Glob": "pattern",
    "Grep": "pattern",
    "Agent": "description",
    "Task": "description",
    "WebFetch": "url",
    "WebSearch": "query",
    "Skill": "skill",
}
EMAIL_ALLOWLIST = {"noreply@anthropic.com"}


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path, help="Claude Code session transcript (.jsonl)")
    parser.add_argument("--denylist", type=Path, required=True, help="file with forbidden terms (kept outside the repository)")
    parser.add_argument("--agent", required=True, help="agent name, e.g. orchestrator, ingestion")
    parser.add_argument("--topic", required=True, help="short topic for the header")
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--include-subagents", action="store_true", help="append subagent transcripts of the session")
    parser.add_argument("--redact", action="append", default=[], help="term to mask with [REDACTED] (after review)")
    parser.add_argument("--drop-turn", action="append", type=int, default=[], help="turn number to replace with a marker")
    parser.add_argument("--result-lines", type=int, default=6, help="max lines kept per tool result")
    parser.add_argument("--line-width", type=int, default=200, help="max characters per kept result line")
    return parser.parse_args(argv)


def read_records(path):
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def strip_system_blocks(text):
    return SYSTEM_BLOCK.sub("", text).strip()


def truncate(text, max_lines, width):
    lines = text.rstrip().splitlines()
    kept = [line if len(line) <= width else line[:width] + " ..." for line in lines[:max_lines]]
    if len(lines) > max_lines:
        kept.append(f"... ({len(lines) - max_lines} more lines)")
    return "\n".join(kept)


def tool_call_line(block):
    name = block.get("name", "tool")
    arguments = block.get("input") or {}
    key = MAIN_ARGUMENT.get(name)
    value = arguments.get(key) if key else next((v for v in arguments.values() if isinstance(v, str)), "")
    value = " ".join(str(value or "").split())
    if len(value) > 300:
        value = value[:300] + " ..."
    return f"**Tool call:** `{name}` — `{value}`" if value else f"**Tool call:** `{name}`"


def result_text(block):
    content = block.get("content")
    if isinstance(content, list):
        content = "\n".join(part.get("text", "[image]") if part.get("type") == "text" else "[image]" for part in content)
    return str(content or "")


def render(records, options):
    """Return a list of turns; each turn is a list of Markdown chunks. Turn 0 holds anything before the first prompt."""
    turns = [[]]
    for record in records:
        if record.get("isMeta") or record.get("type") not in ("user", "assistant"):
            continue
        content = (record.get("message") or {}).get("content")
        if record["type"] == "user":
            if isinstance(content, str):
                text = strip_system_blocks(content)
                if text:
                    turns.append([f"**User:**\n\n{text}"])
                continue
            for block in content or []:
                if block.get("type") == "text":
                    text = strip_system_blocks(block.get("text", ""))
                    if text:
                        turns.append([f"**User:**\n\n{text}"])
                elif block.get("type") == "tool_result":
                    text = truncate(strip_system_blocks(result_text(block)), options.result_lines, options.line_width)
                    label = "Tool error" if block.get("is_error") else "Tool result"
                    turns[-1].append(f"<details><summary>{label}</summary>\n\n```text\n{text}\n```\n\n</details>")
        else:
            for block in content or []:
                if block.get("type") == "text" and block.get("text", "").strip():
                    turns[-1].append(f"**Assistant:**\n\n{block['text'].strip()}")
                elif block.get("type") == "tool_use":
                    turns[-1].append(tool_call_line(block))
    return turns


def sanitize(text, redact_terms):
    text = HOME_PATH.sub("~", text)
    text = EMAIL.sub(lambda m: m.group(0) if m.group(0) in EMAIL_ALLOWLIST else REDACTED, text)
    for term in redact_terms:
        text = re.sub(re.escape(term), REDACTED, text, flags=re.IGNORECASE)
    return text


def load_denylist(path):
    """Return (entry label, pattern) pairs. Each term matches as a whole word, ignoring case.

    The label is the line number in the denylist file, so a report never repeats the term itself.
    """
    entries = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if line and not line.startswith("#"):
            pattern = re.compile(r"(?<!\w)" + re.escape(line) + r"(?!\w)", re.IGNORECASE)
            entries.append((f"denylist line {number}", pattern))
    return entries


def mask_all(line, checks):
    for _, pattern in checks:
        line = pattern.sub("***", line)
    return line


def find_problems(text, denylist):
    """Return (line number, reason, line with every match masked).

    Every check is masked, not only the one that matched, so a line holding two terms never shows either.
    """
    checks = denylist + [(f"possible secret: {kind}", pattern) for kind, pattern in SECRET_PATTERNS.items()]
    problems = []
    for number, line in enumerate(text.splitlines(), start=1):
        reasons = [reason for reason, pattern in checks if pattern.search(line)]
        if reasons:
            problems.append((number, ", ".join(reasons), mask_all(line, checks)))
    return problems


def session_metadata(records):
    timestamps = [r["timestamp"] for r in records if r.get("timestamp")]
    models = sorted({(r.get("message") or {}).get("model") for r in records
                     if r.get("type") == "assistant" and (r.get("message") or {}).get("model")} - {"<synthetic>"})
    return (timestamps[0] if timestamps else "?"), (timestamps[-1] if timestamps else "?"), models


def format_session(records, options, heading_prefix, first_turn):
    turns = render(records, options)
    chunks = []
    for index, turn in enumerate(turns):
        if not turn:
            continue
        number = first_turn + index - 1
        if index > 0:
            chunks.append(f"{heading_prefix} Turn {number}")
        chunks.append(UNRELATED_MARKER if number in options.drop_turn else "\n\n".join(turn))
    return chunks, first_turn + len(turns) - 1


def subagent_files(session_path):
    folder = session_path.with_suffix("") / "subagents"
    return sorted(folder.glob("*.jsonl")) if folder.is_dir() else []


def main(argv):
    options = parse_args(argv)
    records = list(read_records(options.session))
    started, ended, models = session_metadata(records)
    header = [
        f"# AI conversation — {options.agent} — {options.topic}",
        "",
        f"- **Tool:** Claude Code ({', '.join(models) or 'model not recorded'})",
        f"- **Agent:** {options.agent}",
        f"- **Session:** {started} to {ended} (UTC)",
        "- **Export:** `scripts/export-ai-conversation.py`. User messages, assistant replies and tool calls are verbatim;"
        f" tool results are truncated to {options.result_lines} lines; system context and hidden reasoning are not included.",
        "",
        "---",
    ]
    body, next_turn = format_session(records, options, "##", 1)
    for path in subagent_files(options.session) if options.include_subagents else []:
        sub_records = list(read_records(path))
        body.append(f"## Subagent transcript: {path.stem}")
        sub_body, next_turn = format_session(sub_records, options, "###", next_turn)
        body.extend(sub_body)

    text = sanitize("\n".join(header) + "\n\n" + "\n\n".join(body) + "\n", options.redact)
    problems = find_problems(text, load_denylist(options.denylist))
    if problems:
        print(f"Export stopped: {len(problems)} match(es). Review them, then use --redact or --drop-turn.", file=sys.stderr)
        for number, reason, masked_line in problems:
            print(f"  line {number} ({reason}): {masked_line.strip()[:160]}", file=sys.stderr)
        return 2
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(text, encoding="utf-8")
    print(f"Wrote {options.output} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
