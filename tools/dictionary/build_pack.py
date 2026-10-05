#!/usr/bin/env python3
"""Build a reproducible, single-word SQLite pack from Open English WordNet LMF XML.

Usage: python3 tools/dictionary/build_pack.py INPUT.xml.gz OUTPUT_DIRECTORY
Downloads are deliberately outside this script. See README.md for pinned source/licences.
"""
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import xml.etree.ElementTree as ET

VERSION = "oewn-2025-1"
POS = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}


def build(source, output):
    output.mkdir(parents=True, exist_ok=True)
    entries, synsets = [], {}
    with gzip.open(source, "rb") as stream:
        for _, element in ET.iterparse(stream, events=("end",)):
            if element.tag == "LexicalEntry":
                lemma = element.find("Lemma")
                word = lemma.get("writtenForm")
                if not any(c.isspace() for c in word) and len(word) <= 100:
                    pronunciation = lemma.findtext("Pronunciation")
                    entries.append((word, POS[lemma.get("partOfSpeech")], pronunciation,
                                    [s.get("synset") for s in element.findall("Sense")][:4],
                                    [f.get("writtenForm") for f in element.findall("Form")]))
                element.clear()
            elif element.tag == "Synset":
                synsets[element.get("id")] = (
                    element.findtext("Definition"), element.findtext("Example"))
                element.clear()

    grouped, forms = {}, set()
    for word, pos, ipa, sense_ids, inflections in entries:
        key = word.lower().replace("’", "'")
        entry = grouped.setdefault(key, {"headword": word, "ipa": ipa, "groups": []})
        senses = [{"gloss": synsets[s][0], "example": synsets[s][1]} for s in sense_ids
                  if s in synsets and synsets[s][0]]
        if senses:
            entry["groups"].append({"partOfSpeech": pos, "senses": senses})
        for form in inflections:
            if not any(c.isspace() for c in form):
                forms.add((form.lower().replace("’", "'"), key))

    path = output / "english.sqlite"
    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    db.executescript("""
        PRAGMA page_size=4096;
        PRAGMA user_version=1;
        CREATE TABLE entries (headword TEXT NOT NULL PRIMARY KEY, entry_json TEXT NOT NULL);
        CREATE TABLE forms (form TEXT NOT NULL, headword TEXT NOT NULL, PRIMARY KEY (form,headword)) WITHOUT ROWID;
        CREATE TABLE metadata (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
    """)
    db.executemany("INSERT INTO entries VALUES (?,?)", (
        (key, json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
        for key, entry in sorted(grouped.items()) if entry["groups"]))
    db.executemany("INSERT INTO forms VALUES (?,?)", sorted(forms))
    db.executemany("INSERT INTO metadata VALUES (?,?)", [
        ("version", VERSION), ("source", "Open English WordNet 2025"),
        ("licence", "CC BY 4.0; Princeton WordNet licence"),
        ("source_sha256", hashlib.sha256(source.read_bytes()).hexdigest()),
        ("modifications", "Single-word entries; first four senses per part of speech; relations omitted.")])
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = path.stat().st_size
    assert size <= 40_000_000, f"Pack exceeds 40 MB: {size}"
    manifest = {"id": "dictionary-en", "version": VERSION, "files": [{
        "path": "english.sqlite", "url": f"https://github.com/RetRo99/tts-models/releases/download/{VERSION}/english.sqlite",
        "size": size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"bytes": size, "headwords": len(grouped), "forms": len(forms), "manifest": manifest}, indent=2))


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
