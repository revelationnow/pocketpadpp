#!/usr/bin/env python3
"""Copies Notepad++'s function list test files into hosttest/data/functionList.

Notepad++ keeps, for each language, a source file and the function list it expects for it (as JSON).
The host tests run Pocketpad++'s parser over the same files, so the expectation is rewritten here as
plain lines the test can compare with: "L<tab>function" for functions outside classes, in order, then
"N<tab>class<tab>function" class by class.

Usage: tools/vendor_function_list_tests.py <notepad-plus-plus checkout>
"""
import json
import os
import shutil
import sys

# Test folder name -> functionList file name, where they differ.
RULES = {"javascript": "javascript.js", "udl-NppExec": "nppexec"}


def esc(text):
    return text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")


def main():
    source = os.path.join(sys.argv[1], "PowerEditor", "Test", "FunctionList")
    target = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hosttest", "data", "functionList")
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    count = 0
    for name in sorted(os.listdir(source)):
        unit = os.path.join(source, name, "unitTest")
        expected = os.path.join(source, name, "unitTest.expected.result")
        if not (os.path.isfile(unit) and os.path.isfile(expected)):
            continue
        rules = RULES.get(name, name)
        if not os.path.isfile(os.path.join(sys.argv[1], "PowerEditor", "installer", "functionList", rules + ".xml")):
            continue
        with open(expected, encoding="utf-8-sig") as f:
            tree = json.load(f)
        lines = ["L\t" + esc(leaf) for leaf in tree.get("leaves") or []]
        for node in tree.get("nodes") or []:
            lines += ["N\t" + esc(node["name"]) + "\t" + esc(leaf) for leaf in node["leaves"]]
        shutil.copyfile(unit, os.path.join(target, rules + ".txt"))
        with open(os.path.join(target, rules + ".expected"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        count += 1
    print(count, "test files")


if __name__ == "__main__":
    main()
