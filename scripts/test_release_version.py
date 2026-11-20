import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release_version", Path(__file__).with_name("release-version.py"))
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)

SOURCE = '''versionCode = providers.gradleProperty("stilldayVersionCode").map(String::toInt).getOrElse(22)
versionName = providers.gradleProperty("stilldayVersionName").getOrElse("1.0.20")'''


class ReleaseVersionTest(unittest.TestCase):
    def test_reads_both_actual_gradle_defaults(self):
        self.assertEqual((22, "1.0.20"), version.read_version(SOURCE))

    def test_malformed_or_out_of_range_versions_are_rejected(self):
        for source in ["", SOURCE.replace("(22)", "(0)"), SOURCE.replace("(22)", "(2100000001)"), SOURCE.replace("1.0.20", "v1.0.20"), SOURCE.replace("1.0.20", "1.0.020")]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                version.read_version(source)

    def test_tag_must_match_committed_version_exactly(self):
        version.validate_tag("v1.0.20", "1.0.20")
        for tag in ["v1.0.19", "1.0.20", "v1.0.20-beta"]:
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "BEFORE"):
                version.validate_tag(tag, "1.0.20")

    def tag_fixture(self, dirty="", committed=SOURCE, existing=""):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        (root / "app").mkdir()
        (root / "app/build.gradle.kts").write_text(SOURCE, encoding="utf-8")
        calls = []
        def git(command, **kwargs):
            args = command[1:]
            calls.append(args)
            if args[0] == "status": return dirty
            if args[0] == "show": return committed
            if args == ["tag", "--list", "v1.0.20"]: return existing
            if args == ["tag", "v1.0.20"]: return ""
            self.fail(str(args))
        return root, git, calls

    def test_uncommitted_changes_never_create_a_tag(self):
        root, git, calls = self.tag_fixture(dirty=" M app/build.gradle.kts")
        with self.assertRaisesRegex(ValueError, "Uncommitted"):
            version.create_tag(root, "1.0.20", git)
        self.assertFalse(any(x[0] == "tag" for x in calls))

    def test_old_commit_never_creates_a_tag(self):
        root, git, calls = self.tag_fixture(committed=SOURCE.replace("1.0.20", "1.0.19"))
        with self.assertRaisesRegex(ValueError, "committed version"):
            version.create_tag(root, "1.0.20", git)
        self.assertNotIn(["tag", "v1.0.20"], calls)

    def test_existing_tag_is_never_overwritten(self):
        root, git, calls = self.tag_fixture(existing="v1.0.20")
        with self.assertRaisesRegex(ValueError, "already exists"):
            version.create_tag(root, "1.0.20", git)
        self.assertNotIn(["tag", "v1.0.20"], calls)

    def test_clean_matching_commit_creates_only_its_local_tag(self):
        root, git, calls = self.tag_fixture()
        self.assertEqual("v1.0.20", version.create_tag(root, "1.0.20", git))
        self.assertEqual(["tag", "v1.0.20"], calls[-1])
        self.assertFalse(any(x[0] == "push" for x in calls))
