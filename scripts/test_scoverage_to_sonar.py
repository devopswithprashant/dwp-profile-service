import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from scoverage_to_sonar import convert


class ScoverageToSonarTest(unittest.TestCase):
    def test_converts_lines_and_branch_counts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo_root = Path(temporary_directory)
            source = repo_root / "src/main/scala/example/Service.scala"
            source.parent.mkdir(parents=True)
            source.write_text("object Service\n")

            report = repo_root / "scoverage.xml"
            report.write_text(
                """<scoverage>
                  <class filename="example/Service.scala">
                    <statement line="7" invocation-count="1" ignored="false" branch="false"/>
                    <statement line="8" invocation-count="0" ignored="false" branch="true"/>
                    <statement line="8" invocation-count="2" ignored="false" branch="true"/>
                    <statement line="9" invocation-count="0" ignored="true" branch="false"/>
                    <statement line="10" invocation-count="0" ignored="false" branch="false"/>
                  </class>
                </scoverage>"""
            )
            output = repo_root / "target/sonar/coverage.xml"

            file_count, line_count = convert(report, output, repo_root)

            self.assertEqual((1, 3), (file_count, line_count))
            root = ET.parse(output).getroot()
            self.assertEqual("1", root.get("version"))
            file_node = root.find("file")
            self.assertIsNotNone(file_node)
            self.assertEqual("src/main/scala/example/Service.scala", file_node.get("path"))
            lines = {node.get("lineNumber"): node.attrib for node in file_node.findall("lineToCover")}
            self.assertEqual("true", lines["7"]["covered"])
            self.assertEqual("true", lines["8"]["covered"])
            self.assertEqual("2", lines["8"]["branchesToCover"])
            self.assertEqual("1", lines["8"]["coveredBranches"])
            self.assertNotIn("9", lines)
            self.assertEqual("false", lines["10"]["covered"])


if __name__ == "__main__":
    unittest.main()
