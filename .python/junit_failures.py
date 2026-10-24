"""List failing test cases from Gradle JUnit XML result directories, or diff two runs.

usage: py -3.12 -I .python/junit_failures.py <results-root> [<other-results-root>]
A results root is a project directory; every */build/test-results/test/*.xml under it is read.
With two roots the second one is the newer run: the output lists the cases failing only there (new),
only in the first (fixed) and in both. Test names are normalized (parameter index, quotes, hashes) so
that the same case matches between runs.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BACKSLASH = chr(92)


def normalize(name):
    if name.startswith("["):
        end = name.find("] ")
        if end > 1 and name[1:end].isdigit():
            name = name[end + 2:]
    name = name.replace('"', "").replace(BACKSLASH * 2, BACKSLASH)
    return re.sub("@[0-9a-f]+", "", name)


def collect(root):
    failed, total, skipped = {}, 0, 0
    for xml in Path(root).glob("*/build/test-results/test/*.xml"):
        module = xml.parts[-5]
        for case in ET.parse(xml).getroot().iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                key = f"{module} | {case.get('classname')} | {normalize(case.get('name'))}"
                message = (problem.get("message") or "").strip().splitlines()
                failed[key] = message[0][:160] if message else problem.get("type", "")
    return failed, total, skipped


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    a, total_a, skipped_a = collect(sys.argv[1])
    print(f"A: {total_a} cases, {len(a)} failed, {skipped_a} skipped")
    if len(sys.argv) < 3:
        for key in sorted(a):
            print("  ", key, "::", a[key])
        return
    b, total_b, skipped_b = collect(sys.argv[2])
    print(f"B: {total_b} cases, {len(b)} failed, {skipped_b} skipped")
    print("== failing only in B (new):")
    for key in sorted(set(b) - set(a)):
        print("  ", key, "::", b[key])
    print("== failing only in A (fixed in B):")
    for key in sorted(set(a) - set(b)):
        print("  ", key, "::", a[key])
    print("== failing in both:", len(set(a) & set(b)))
    for key in sorted(set(a) & set(b)):
        print("  ", key)


if __name__ == "__main__":
    main()
