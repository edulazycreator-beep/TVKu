#!/usr/bin/env python3
"""Summarises JUnit and Android lint XML reports as GitHub annotations.

Prints one ::notice with per-module test counts and lint issue counts, and
fails (exit 1) if no unit tests ran at all, so a green build can never mean
"zero tests executed".
"""
import collections
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
tests = collections.defaultdict(lambda: [0, 0, 0, 0])  # tests, failures, errors, skipped
for xml in root.glob("**/build/test-results/**/TEST-*.xml"):
    module = xml.relative_to(root).parts[: xml.relative_to(root).parts.index("build")]
    s = ET.parse(xml).getroot()
    t = tests["/".join(module)]
    for i, attr in enumerate(("tests", "failures", "errors", "skipped")):
        t[i] += int(s.get(attr, 0))

lint = collections.Counter()
for xml in root.glob("**/build/reports/lint-results-*.xml"):
    for issue in ET.parse(xml).getroot().iter("issue"):
        lint[issue.get("severity", "?")] += 1

lines = [f"{m}: {t[0]} tests, {t[1]} failures, {t[2]} errors, {t[3]} skipped" for m, t in sorted(tests.items())]
total = sum(t[0] for t in tests.values())
lines.append(f"TOTAL unit tests executed: {total}")
lines.append("Lint issues: " + (", ".join(f"{k}={v}" for k, v in sorted(lint.items())) or "none"))
print("\n".join(lines))
print("::notice title=Test and lint summary::" + "%0A".join(lines))
sys.exit(0 if total > 0 else 1)
