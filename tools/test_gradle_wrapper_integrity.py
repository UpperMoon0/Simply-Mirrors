"""Assert all four Gradle wrappers use reviewed official distribution checksums."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "8.14": "61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa",
    "9.2.1": "72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f",
}
ROOTS = {
    "gradle/wrapper/gradle-wrapper.properties": "8.14",
    "forge-1.16.5/gradle/wrapper/gradle-wrapper.properties": "8.14",
    "fabric-26.1.2/gradle/wrapper/gradle-wrapper.properties": "9.2.1",
    "neoforge-26.1.2/gradle/wrapper/gradle-wrapper.properties": "9.2.1",
}


class WrapperIntegrityTests(unittest.TestCase):
    def test_distributions_have_sha256_pins(self):
        for name, version in ROOTS.items():
            with self.subTest(root=name):
                props = dict(
                    line.split("=", 1) for line in (ROOT / name).read_text().splitlines()
                    if "=" in line and not line.startswith("#")
                )
                self.assertEqual(
                    props["distributionUrl"].replace(r"\:", ":"),
                    f"https://services.gradle.org/distributions/gradle-{version}-bin.zip",
                )
                self.assertEqual(props["distributionSha256Sum"], EXPECTED[version])
                self.assertRegex(props["distributionSha256Sum"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
