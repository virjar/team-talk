"""Exercise the CI helper against temporary Git histories; no network or Gradle required."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from release_history import comparison_base, git, resolve


class ReleaseHistoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="teamtalk-ci-history-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.root.mkdir()
        git(self.root, "init", "-q")
        for key, value in (
            ("user.name", "Release history test"), ("user.email", "test@example.invalid"),
            ("commit.gpgsign", "false"), ("tag.gpgsign", "false"),
            ("core.hooksPath", str(self.root / ".git/disabled-hooks")),
        ):
            git(self.root, "config", key, value)
        self.common = self.commit("initial.txt", "Shared history")

    def commit(self, name, content):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        git(self.root, "add", "--all")
        git(self.root, "commit", "-qm", content)
        return git(self.root, "rev-parse", "HEAD").stdout.strip()

    def test_normal_push_and_pull_request_keep_the_requested_base(self):
        before = self.commit("before.txt", "Before push")
        self.commit("change.txt", "Current change")
        self.assertEqual(before, resolve(self.root, before))
        self.assertEqual(self.common, resolve(self.root, self.common))

    def test_rewrite_uses_common_ancestor_without_reclaiming_version_tags(self):
        old = self.commit("protocol/protocol/releases/0.0.0/wire-baseline.tsv", "Old untagged draft")
        git(self.root, "checkout", "-q", "-b", "rewritten", self.common)
        self.commit("protocol/protocol/releases/0.0.0/wire-baseline.tsv", "Reviewed first release")
        self.assertEqual(self.common, resolve(self.root, old))

    def test_non_ancestor_release_tags_protect_both_kinds_of_frozen_record(self):
        for folder in ("releases/0.0.0", "contracts/0.1"):
            with self.subTest(folder=folder):
                git(self.root, "checkout", "-q", "--detach", self.common)
                self.commit(f"protocol/protocol/{folder}/wire-baseline.tsv", "Published contract")
                git(self.root, "tag", "v0.0.0")
                git(self.root, "checkout", "-q", "--detach", self.common)
                self.commit("rewrite.txt", "Rewritten branch")
                with self.assertRaisesRegex(RuntimeError, "from v0.0.0"):
                    resolve(self.root, self.common)
                git(self.root, "tag", "-d", "v0.0.0")

    def test_tags_allow_unchanged_history_and_new_records_but_reject_mutation(self):
        record = "protocol/protocol/releases/0.0.0/release.properties"
        published = self.commit(record, "Original release")
        git(self.root, "tag", "-a", "v0.0.0", "-m", "Published")
        self.commit("protocol/protocol/contracts/0.1/contract.properties", "Additional contract")
        self.assertEqual(published, resolve(self.root, published))
        self.commit(record, "Changed published release")
        with self.assertRaisesRegex(RuntimeError, "from v0.0.0"):
            resolve(self.root, "")

    def test_missing_former_tip_is_fetched_by_verified_sha_from_local_origin(self):
        old = self.commit("old.txt", "Former remote tip")
        remote = Path(self.temporary.name) / "origin.git"
        subprocess.run(["git", "clone", "-q", "--bare", str(self.root), str(remote)], check=True, capture_output=True)
        git(self.root, "checkout", "-q", "--detach", self.common)
        self.commit("new.txt", "New branch")
        clone = Path(self.temporary.name) / "clone"
        subprocess.run(["git", "clone", "-q", "--no-local", "--single-branch", str(self.root), str(clone)], check=True, capture_output=True)
        git(clone, "remote", "set-url", "origin", str(remote))
        self.assertNotEqual(0, git(clone, "cat-file", "-e", old, check=False).returncode)
        self.assertEqual(self.common, resolve(clone, old))
        self.assertEqual(0, git(clone, "cat-file", "-e", old, check=False).returncode)

    def test_invalid_or_unrelated_bases_fail_without_skipping_checks(self):
        for invalid in ("HEAD~1", "--upload-pack=command", "a" * 39, "a" * 40 + "\n"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                comparison_base(self.root, invalid)
        git(self.root, "checkout", "-q", "--orphan", "unrelated")
        self.commit("unrelated.txt", "No shared ancestor")
        with self.assertRaisesRegex(RuntimeError, "common ancestor"):
            resolve(self.root, self.common)

    def test_new_branch_and_manual_release_have_no_comparison_base(self):
        self.assertEqual("", resolve(self.root, ""))
        self.assertEqual("", resolve(self.root, "0" * 40))


if __name__ == "__main__":
    unittest.main()
