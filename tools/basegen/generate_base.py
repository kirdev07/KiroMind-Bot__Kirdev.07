"""Builds the built-in KiroMind answer base from topics.txt.

Each topic gets its hand-written phrasings plus variants with conversational fillers
("слушай", "а", "бот" ...), so MindCore matches more ways of asking the same thing.
All entries of one topic share the same replies; MindCore treats them as one topic.

Usage: python tools/basegen/generate_base.py [target_count]
Output: app/src/main/assets/base/kiromind-base.json (compact: replies stored once per topic;
the app expands it into answers, see BuiltInBase.kt)
"""
import json, math, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(__file__).with_name("topics.txt")
OUT = ROOT / "app/src/main/assets/base/kiromind-base.json"
# Bump when the content changes: installed apps merge a newer pack once.
PACK_VERSION = 1
TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 30_000

PREFIXES = ["", "а", "слушай", "скажи", "ну", "эй", "кстати", "бот", "киро", "слышь", "так", "короче",
            "а скажи", "слушай бот", "скажи мне", "эй бот", "а вот", "ну а", "бот скажи", "киро скажи"]
SUFFIXES = ["", "бот", "киро", "а", "вообще", "ну", "братан", "друг", "пожалуйста", "кстати", "скажи", "мне"]
STYLES = {"N": "NEUTRAL", "F": "FRIENDLY", "P": "POLITE", "C": "CHEEKY"}


def normalize(s: str) -> str:
    """Same as TextNormalizer.normalize in the app."""
    s = s.lower().replace("ё", "е")
    s = re.sub(r"[^\w]+|_", " ", s)
    s = re.sub(r"(\w)\1{2,}", r"\1", s)
    return " ".join(s.split())


def parse():
    topics, category, cur = [], "", None
    for raw in SRC.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            name = line[1:].strip()
            # Comment lines of the header contain "—" or ":"; category lines are short names.
            if name and len(name) <= 40 and "—" not in name and ":" not in name:
                category = name
            continue
        if line.startswith("?"):
            cur = {"category": category, "phrases": [p.strip() for p in line[1:].split("|") if p.strip()], "replies": []}
            topics.append(cur)
            continue
        key, _, text = line.partition(":")
        if key in STYLES and cur is not None:
            cur["replies"] += [(STYLES[key], t.strip()) for t in text.split("||") if t.strip()]
    for t in topics:
        assert t["replies"] and t["replies"][0][0] == "NEUTRAL", t["phrases"]
    return topics


def candidates(phrase):
    """Bare phrase first, then fillers spread so every phrasing gets varied company."""
    for p in PREFIXES:
        for s in SUFFIXES:
            if p or s:
                yield " ".join(x for x in (p, phrase, s) if x)


def build(topics):
    seen, per_topic = set(), [[] for _ in topics]

    def take(i, text):
        n = normalize(text)
        if n and n not in seen:
            seen.add(n)
            per_topic[i].append(text)
            return True
        return False

    # Pass 1: every hand-written phrasing, so a filler variant never steals another topic's phrase.
    for i, t in enumerate(topics):
        for p in t["phrases"]:
            take(i, p)
    # Pass 2: fill each topic up to its share, round-robin over its phrasings.
    quota = math.ceil(TARGET / len(topics))
    for i, t in enumerate(topics):
        gens = [candidates(p) for p in t["phrases"]]
        while len(per_topic[i]) < quota and gens:
            for g in list(gens):
                if len(per_topic[i]) >= quota:
                    break
                for text in g:
                    if take(i, text):
                        break
                else:
                    gens.remove(g)

    # Trim to the exact target from the end, keeping every topic's bare phrasings.
    total = sum(map(len, per_topic))
    for qs in reversed(per_topic):
        while total > TARGET and len(qs) > 1:
            qs.pop(); total -= 1
    return [{"category": t["category"], "replies": [[style, text] for style, text in t["replies"]], "questions": qs}
            for t, qs in zip(topics, per_topic)]


def main():
    topics = parse()
    packed = build(topics)
    doc = {"format": "kiromind-base", "version": PACK_VERSION, "topics": packed}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    OUT.write_bytes(data)  # the APK zips assets itself
    cats = sorted({t["category"] for t in topics})
    print(f"topics={len(topics)} entries={sum(len(t['questions']) for t in packed)} categories={len(cats)} json={len(data) // 1024}KB")


if __name__ == "__main__":
    main()
