"""Tests for scripts/export-ai-conversation.py. Run with: python3 -m unittest discover -s scripts/tests"""

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "export-ai-conversation.py"
spec = importlib.util.spec_from_file_location("export_ai_conversation", SCRIPT)
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)


def write_lines(path, lines):
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def user(text):
    return {"type": "user", "message": {"content": text}}


def assistant(*blocks):
    return {"type": "assistant", "message": {"model": "test-model", "content": list(blocks)}}


class ExportTestCase(unittest.TestCase):

    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.denylist = self.folder / "denylist.txt"
        write_lines(self.denylist, ["# comment", "", "alpha", "Beta Corp"])
        self.output = self.folder / "out.md"

    def run_export(self, records, *extra):
        session = self.folder / "session.jsonl"
        write_lines(session, [json.dumps(r) for r in records])
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            code = export.main([str(session), "--denylist", str(self.denylist), "--agent", "orchestrator",
                                "--topic", "test", "-o", str(self.output), *extra])
        return code, stderr.getvalue()


class DenylistReportTest(ExportTestCase):

    def test_report_never_shows_any_denylisted_term_when_one_line_holds_several(self):
        code, report = self.run_export([user("alpha met Beta Corp today")])
        self.assertEqual(2, code)
        self.assertNotIn("alpha", report.lower())
        self.assertNotIn("beta corp", report.lower())
        self.assertIn("denylist line 3, denylist line 4", report)
        self.assertEqual(1, report.count("  line "))
        self.assertFalse(self.output.exists())

    def test_report_masks_secrets_and_terms_on_the_same_line(self):
        token = "ghp_" + "a" * 30
        code, report = self.run_export([user(f"alpha {token}")])
        self.assertEqual(2, code)
        self.assertNotIn(token, report)
        self.assertNotIn("alpha", report)
        self.assertIn("possible secret: GitHub token", report)

    def test_terms_match_whole_words_only_ignoring_case(self):
        code, _ = self.run_export([user("alphabet soup")])
        self.assertEqual(0, code)
        code, _ = self.run_export([user("ALPHA.")])
        self.assertEqual(2, code)

    def test_redact_masks_a_reviewed_term_and_lets_the_export_pass(self):
        code, _ = self.run_export([user("alpha and Beta Corp")], "--redact", "alpha", "--redact", "beta corp")
        self.assertEqual(0, code)
        text = self.output.read_text(encoding="utf-8")
        self.assertIn("[REDACTED] and [REDACTED]", text)

    def test_drop_turn_replaces_the_whole_exchange_with_the_marker(self):
        code, _ = self.run_export([user("first"), user("alpha unrelated"), user("third")], "--drop-turn", "2")
        self.assertEqual(0, code)
        text = self.output.read_text(encoding="utf-8")
        self.assertIn(export.UNRELATED_MARKER, text)
        self.assertNotIn("unrelated", text.replace(export.UNRELATED_MARKER, ""))
        self.assertIn("third", text)


class SanitizeTest(unittest.TestCase):

    def test_home_paths_and_emails_are_masked_but_the_attribution_address_is_kept(self):
        text = export.sanitize("/Users/someone/x and /home/other/y mail a.b@example.org noreply@anthropic.com", [])
        self.assertEqual("~/x and ~/y mail [REDACTED] noreply@anthropic.com", text)


class RenderTest(ExportTestCase):

    def test_system_context_meta_records_and_reasoning_are_dropped(self):
        records = [
            {"type": "user", "isMeta": True, "message": {"content": "meta context"}},
            user("<system-reminder>hidden reminder</system-reminder>visible prompt"),
            assistant({"type": "thinking", "thinking": "private chain of thought"},
                      {"type": "text", "text": "visible answer"},
                      {"type": "tool_use", "name": "Bash", "input": {"command": "ls -la"}}),
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "content": "\n".join(f"row {i}" for i in range(10))}]}},
        ]
        code, _ = self.run_export(records, "--result-lines", "3")
        self.assertEqual(0, code)
        text = self.output.read_text(encoding="utf-8")
        for hidden in ("meta context", "hidden reminder", "private chain of thought", "row 3"):
            self.assertNotIn(hidden, text)
        for shown in ("visible prompt", "visible answer", "`Bash` — `ls -la`", "row 2", "(7 more lines)"):
            self.assertIn(shown, text)
        self.assertIn("test-model", text)


if __name__ == "__main__":
    unittest.main()
