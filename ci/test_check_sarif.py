import unittest
from check_sarif import blocking_findings


def report(score=None, level="warning", results=True):
    properties = {} if score is None else {"security-severity": score}
    return {"runs": [{"tool": {"driver": {"rules": [
        {"id": "test/rule", "properties": properties, "defaultConfiguration": {"level": level}}
    ]}}, "results": [{"ruleId": "test/rule"}] if results else []}]}


class SecurityGateTests(unittest.TestCase):
    def test_clean_report_and_low_severity_pass(self):
        self.assertEqual(blocking_findings(report("9.8", results=False)), [])
        self.assertEqual(blocking_findings(report("6.9")), [])

    def test_high_critical_and_quality_errors_block(self):
        for score in ("7.0", "8.2", "10"):
            self.assertEqual(blocking_findings(report(score)), ["test/rule"])
        self.assertEqual(blocking_findings(report(level="error")), ["test/rule"])

    def test_rule_index_and_result_level(self):
        data = report("8")
        data["runs"][0]["results"] = [{"ruleIndex": 0}]
        self.assertEqual(blocking_findings(data), ["test/rule"])
        data = report()
        data["runs"][0]["results"][0]["level"] = "error"
        self.assertEqual(blocking_findings(data), ["test/rule"])

    def test_incomplete_or_failed_analysis_does_not_pass(self):
        for data in ({}, {"runs": []}, report("NaN"), report("11")):
            with self.assertRaises(ValueError):
                blocking_findings(data)
        data = report(results=False)
        data["runs"][0]["invocations"] = [{"executionSuccessful": False}]
        with self.assertRaises(ValueError):
            blocking_findings(data)

    def test_codeql_query_pack_extension_severity_is_enforced(self):
        data = report("8.2")
        tool = data["runs"][0]["tool"]
        tool["extensions"] = [{"name": "codeql/java-queries", "rules": tool["driver"].pop("rules")}]
        # Severity is not dependent on the optional result-level field.
        self.assertEqual(blocking_findings(data), ["test/rule"])
        data["runs"][0]["results"] = [{"rule": {"index": 0, "toolComponent": {"index": 0}}}]
        self.assertEqual(blocking_findings(data), ["test/rule"])
