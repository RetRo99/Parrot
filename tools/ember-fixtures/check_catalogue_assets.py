#!/usr/bin/env python3
"""Verify actual Android packaging, not just the preset parser's input."""
import argparse
import json
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as apk:
    presets = json.loads(apk.read("assets/composeResources/resources.catalogue.ui/files/catalogue-presets.json"))
    assert len(presets) == 2
    assert [p["id"] for p in presets if p.get("listEntriesAreBooks", False)] == ["project-gutenberg"]
    assert all(p["address"].startswith("https://") for p in presets)
print("PASS: both catalogue presets packaged; only Project Gutenberg has the book-link hint")
