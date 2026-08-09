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
        for forbidden in ("latest.release", "latest.integration", "+\""):
            self.assertNotIn(forbidden, catalog)


if __name__ == "__main__":
    unittest.main()
