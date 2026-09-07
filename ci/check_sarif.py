"""Fail CI on CodeQL High/Critical security findings or error-level quality findings."""

import json
import math
from pathlib import Path
import sys


def blocking_findings(report):
    runs = report.get("runs")
    if not isinstance(runs, list) or not runs:
        raise ValueError("SARIF has no analysis runs")
    blocked = []
    for run in runs:
        if any(item.get("executionSuccessful") is False for item in run.get("invocations", [])):
            raise ValueError("SARIF reports an unsuccessful analysis")
        tool = run["tool"]
        rules = tool["driver"].get("rules", [])
        extensions = tool.get("extensions", [])
        # CodeQL query packs store rule metadata in extensions, not the driver.
        rules = rules + [rule for component in extensions for rule in component.get("rules", [])]
        by_id = {rule["id"]: rule for rule in rules}
        if not isinstance(run.get("results"), list):
            raise ValueError("SARIF has no results array")
        for finding in run["results"]:
            reference = finding.get("rule", {})
            rule = by_id.get(finding.get("ruleId", reference.get("id")), {})
            index = finding.get("ruleIndex")
            indexed_rules = tool["driver"].get("rules", [])
            component = reference.get("toolComponent", {}).get("index")
            if isinstance(component, int) and 0 <= component < len(extensions):
                indexed_rules = extensions[component].get("rules", [])
                index = reference.get("index")
            if not rule and isinstance(index, int) and 0 <= index < len(indexed_rules):
                rule = indexed_rules[index]
            score = rule.get("properties", {}).get("security-severity")
            if score is not None:
                score = float(score)
                if not math.isfinite(score) or not 0 <= score <= 10:
                    raise ValueError("Invalid security severity")
            level = finding.get("level", rule.get("defaultConfiguration", {}).get("level", "warning"))
            if (score is not None and score >= 7) or level == "error":
                blocked.append(finding.get("ruleId", rule.get("id", "unknown-rule")))
    return blocked


def main(directory):
    reports = sorted(Path(directory).glob("*.sarif"))
    if not reports:
        print("Security gate failed: no SARIF reports were produced", file=sys.stderr)
        return 2
    try:
        blocked = [rule for path in reports for rule in blocking_findings(json.loads(path.read_text()))]
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Security gate failed: invalid/incomplete SARIF ({type(error).__name__})", file=sys.stderr)
        return 2
    if blocked:
        print("Blocking CodeQL rules: " + json.dumps(blocked))
        return 1
    print("Security gate passed: no High/Critical or error-level CodeQL findings")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
