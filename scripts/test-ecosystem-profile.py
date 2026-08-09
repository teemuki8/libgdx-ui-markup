#!/usr/bin/env python3
"""Repository contract tests for exact ecosystem compatibility profiles."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class EcosystemProfileContractTest(unittest.TestCase):
    def test_exact_profiles_and_tasks_are_declared(self):
        catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn('harness = "1.2.0"', catalog)
        self.assertIn('agent-runtime = "2.0.0"', catalog)
        self.assertIn('ecosystemProfile', build)
        self.assertIn('"minimum" to EcosystemVersions("1.1.0", "1.0.0")', build)
        self.assertIn('"current" to EcosystemVersions("1.2.0", "2.0.0")', build)
        self.assertIn('tasks.register<GradleBuild>("minimumEcosystemTest")', build)
        self.assertIn('tasks.register<GradleBuild>("currentEcosystemTest")', build)

    def test_no_dynamic_teemuki8_versions_are_declared(self):
        catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        verification = (ROOT / "gradle/verification-metadata.xml").read_text(
            encoding="utf-8")
        for forbidden in ("latest.release", "latest.integration", "+\""):
            self.assertNotIn(forbidden, catalog)
        self.assertNotIn('<trusting group="io.github.teemuki8"/>', verification)

    def test_ci_and_release_execute_both_profiles(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        for task in ("minimumEcosystemTest", "currentEcosystemTest"):
            self.assertIn(task, ci)
            self.assertIn(task, release)

    def test_current_and_minimum_stack_are_documented(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        embedding = (ROOT / "docs/guides/embedding.md").read_text(encoding="utf-8")
        release_note = ROOT / "docs/releases/v0.4.1.md"
        self.assertIn("Current tested stack", readme)
        self.assertIn("Minimum compatible stack", readme)
        self.assertIn("harness 1.2.0", embedding)
        self.assertIn("agent-runtime 2.0.0", embedding)
        self.assertTrue(release_note.is_file())


if __name__ == "__main__":
    unittest.main()
