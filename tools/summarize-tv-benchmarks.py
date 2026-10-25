#!/usr/bin/env python3
"""Summarize AndroidJUnitRunner and Macrobenchmark artifacts without publishing UI content."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directories", nargs="+", type=Path)
args = parser.parse_args()
output = []
for directory in args.directories:
    record = {"directory": str(directory), "tests": [], "benchmarks": []}
    for path in sorted(directory.rglob("TEST-*.xml")):
        for case in ET.parse(path).getroot().iter("testcase"):
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else (
                "skipped" if case.find("skipped") is not None else "passed")
            record["tests"].append({"class": case.get("classname"), "name": case.get("name"), "status": status})
    if not record["tests"]:
        # Direct AndroidJUnitRunner execution avoids rebuilding/redeploying a
        # target while comparing two already-built APKs.
        cases = {}
        for path in sorted(directory.rglob("instrumentation.log")):
            block = {}
            for line in path.read_text(errors="replace").splitlines():
                if line.startswith("INSTRUMENTATION_STATUS: "):
                    key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
                    block[key] = value
                elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
                    code = int(line.rsplit(":", 1)[1])
                    if "test" in block and "class" in block:
                        key = (block["class"], block["test"])
                        status = "incomplete" if code == 1 else (
                            "passed" if code == 0 else "skipped" if code in (-3, -4) else "failed")
                        cases[key] = {"class": key[0], "name": key[1], "status": status}
                    block = {}
        record["tests"] = list(cases.values())
    for path in sorted(directory.rglob("*benchmarkData.json")):
        for case in json.loads(path.read_text()).get("benchmarks", []):
            record["benchmarks"].append({
                "class": case.get("className"), "name": case.get("name"),
                "iterations": case.get("repeatIterations"),
                "metrics": {name: {key: value for key, value in metric.items() if key != "runs"}
                            for name, metric in case.get("sampledMetrics", {}).items()},
                "frame_count": case.get("metrics", {}).get("frameCount", {}).get("median"),
            })
    record["counts"] = {status: sum(test["status"] == status for test in record["tests"])
                        for status in ("passed", "failed", "skipped", "incomplete")}
    output.append(record)
print(json.dumps(output, indent=2, ensure_ascii=False))
