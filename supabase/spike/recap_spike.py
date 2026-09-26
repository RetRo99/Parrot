#!/usr/bin/env python3
"""
Recap spike — tests the thing that actually matters: does a cheap model
produce a recap worth showing a reader?

This deliberately does NOT test the Edge Function plumbing. That is mechanical
and has a working precedent in this repo (delete-cloud-account). This tests the
prompt + model + cost triangle, because if the prose is bad the rest is moot.

Uses only the Python standard library. No pip install.

Usage:
    export RECAP_API_KEY=sk-...
    python3 supabase/spike/recap_spike.py

    # pick provider/model/endpoint
    RECAP_PROVIDER=gemini RECAP_API_KEY=... python3 supabase/spike/recap_spike.py
    RECAP_PROVIDER=openai RECAP_MODEL=qwen3.7-flash \
      RECAP_BASE_URL=https://openrouter.ai/api/v1 \
      RECAP_API_KEY=... python3 supabase/spike/recap_spike.py

    # just one case
    python3 supabase/spike/recap_spike.py --case 2

The prompt below is copied verbatim from
supabase/functions/generate-recap/index.ts so what you judge here is what ships.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------- config

PROVIDER = os.environ.get("RECAP_PROVIDER", "openai")
BASE_URL = os.environ.get("RECAP_BASE_URL", "https://api.openai.com/v1")
API_KEY = os.environ.get("RECAP_API_KEY")
MODEL = os.environ.get("RECAP_MODEL") or (
    "gemini-3.1-flash-lite" if PROVIDER == "gemini" else "gpt-5-nano"
)

MAX_EXCERPT_CHARS = 8_000
MAX_OUTPUT_TOKENS = 160
TEMPERATURE = 0.4

# $ per 1M tokens (input, output). Used only for the estimate line.
PRICES = {
    "qwen3.7-flash": (0.03, 0.13),
    "gpt-5-nano": (0.05, 0.40),
    "gpt-5-mini": (0.25, 2.00),
    "gemini-3.1-flash-lite": (0.25, 1.50),
    "gemini-2.5-flash-lite": (0.10, 0.40),
    "deepseek-v4-flash": (0.22, 0.66),
    "glm-4.7-flash": (0.0, 0.0),
}

# ---------------------------------------------------------------- prompt
# KEEP IN SYNC WITH index.ts

def build_prompt(book_title, chapter_titles, last_sentence, excerpt):
    lines = [
        "You are helping a reader resume a book they were reading.",
        "Summarise ONLY the passage below in 2-3 sentences of plain prose.",
        "Write in present tense, second person (\"you\").",
        "No headings, no bullet points, no preamble such as \"In this passage\".",
        "Do not invent anything that is not in the passage.",
        "If the passage is too fragmentary to summarise, say so in one short sentence.",
        "",
        f"Book: {book_title}",
    ]
    if chapter_titles:
        lines.append(f"Chapters read: {', '.join(chapter_titles)}")
    if last_sentence:
        lines.append(f"The reader stopped at: \"{last_sentence}\"")
    lines += ["", "Passage:", excerpt[:MAX_EXCERPT_CHARS]]
    return "\n".join(lines)


# ---------------------------------------------------------------- cases
# Public-domain passages. Each one is chosen to break a different failure mode.

CASES = [
    {
        "name": "1. Straight narrative (the easy one)",
        "book": "Pride and Prejudice",
        "chapters": ["Chapter 1"],
        "last": "he is considered as the rightful property of some one or other of their daughters.",
        "excerpt": (
            "It is a truth universally acknowledged, that a single man in possession of a "
            "good fortune, must be in want of a wife.\n\n"
            "However little known the feelings or views of such a man may be on his first "
            "entering a neighbourhood, this truth is so well fixed in the minds of the "
            "surrounding families, that he is considered as the rightful property of some "
            "one or other of their daughters.\n\n"
            "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that "
            "Netherfield Park is let at last?\"\n\n"
            "Mr. Bennet replied that he had not.\n\n"
            "\"But it is,\" returned she; \"for Mrs. Long has just been here, and she told "
            "me all about it.\"\n\n"
            "Mr. Bennet made no answer.\n\n"
            "\"Do not you want to know who has taken it?\" cried his wife impatiently.\n\n"
            "\"You want to tell me, and I have no objection to hearing it.\"\n\n"
            "This was invitation enough.\n\n"
            "\"Why, my dear, you must know, Mrs. Long says that Netherfield is taken by a "
            "young man of large fortune from the north of England; that he came down on "
            "Monday in a chaise and four to see the place, and was so much delighted with "
            "that he agreed with Mr. Morris immediately; that he is to take possession "
            "before Michaelmas, and some of his servants are to be in the house by the end "
            "of next week.\""
        ),
    },
    {
        "name": "2. Dense descriptive prose (tests compression)",
        "book": "Moby-Dick",
        "chapters": ["Chapter 1"],
        "last": "you will find that you are in the land of dreams.",
        "excerpt": (
            "Call me Ishmael. Some years ago—never mind how long precisely—having little "
            "or no money in my purse, and nothing particular to interest me on shore, I "
            "thought I would sail about a little and see the watery part of the world. It "
            "is a way I have of driving off the spleen and regulating the circulation. "
            "Whenever I find myself growing grim about the mouth; whenever it is a damp, "
            "drizzly November in my soul; whenever I find myself involuntarily pausing "
            "before coffin warehouses, and bringing up the rear of every funeral I meet; "
            "and especially whenever my hypos get such an upper hand of me, that it "
            "requires a strong moral principle to prevent me from deliberately stepping "
            "into the street, and methodically knocking people's hats off—then, I account "
            "it high time to get to sea as soon as I can.\n\n"
            "This is my substitute for pistol and ball. With a philosophical flourish Cato "
            "throws himself upon his sword; I quietly take to the ship. There is nothing "
            "surprising in this. If they but knew it, almost all men in their degree, some "
            "time or other, cherish very nearly the same feelings towards the ocean with "
            "me.\n\n"
            "There now is your insular city of the Manhattoes, belted round by wharves as "
            "Indian isles by coral reefs—commerce surrounds it with her surf. Right and "
            "left, the streets take you waterward. Its extreme downtown is the battery, "
            "where that noble mole is washed by waves, and cooled by breezes, which a few "
            "hours previous were out of sight of land. Look at the crowds of water-gazers "
            "there."
        ),
    },
    {
        "name": "3. Dialogue-heavy (tests attribution)",
        "book": "Frankenstein",
        "chapters": ["Letter 2"],
        "last": "I shall try to gain a tidings of you.",
        "excerpt": (
            "How slowly the time passes here, encompassed as I am by frost and snow! I "
            "have already hired a vessel, and am occupied in collecting my sailors; but "
            "those sailors, whom I have persuaded to leave their homes and encounter "
            "the hardships of the sea, are still exposed to the dangers of that element.\n\n"
            "I have no friend, Margaret: when I am glowing with the enthusiasm of success, "
            "there will be none to participate my joy; if I am assailed by disappointment, "
            "no one will endeavour to sustain me in dejection. I shall commit my thoughts "
            "to paper, it is true; but that is a poor medium for the communication of "
            "feeling. I desire the company of a man who could sympathise with me, whose "
            "eyes would reply to mine.\n\n"
            "You may deem me romantic, my dear sister, but I bitterly feel the want of a "
            "friend. I have no one near me, gentle yet courageous, possessed of a "
            "cultivated as well as of a capacious mind, whose tastes are like my own, to "
            "approve or amend my plans. How would such a friend repair the faults of your "
            "poor brother!\n\n"
            "I am too ardent in execution, and too impatient of difficulties. But it is a "
            "still greater evil to me that I am self-educated: for the first fourteen "
            "years of my life I ran wild on a common, and read nothing but our Uncle "
            "Thomas' books of voyages."
        ),
    },
    {
        "name": "4. Mid-scene fragment (tests robustness with no context)",
        "book": "Dracula",
        "chapters": ["Chapter 3"],
        "last": "the last I saw of them was a white face and red eyes.",
        "excerpt": (
            "The grey of the morning has passed, and the sun is high over the distant "
            "horizon, which seems jagged, whether with trees or hills I know not, for it "
            "is so far off that big things and little are mixed.\n\n"
            "I am not sleepy, and, as I am not to be called till I awake, naturally I "
            "write till sleep comes. There are many odd things to put down, and, lest "
            "whoever reads them may fancy that I dined too well before I left Bistritz, "
            "let me put down my dinner exactly. I dined on what they called \"robber "
            "steak\"—bits of bacon, onion, and beef, seasoned with red pepper, and "
            "strung on sticks, and roasted over the fire, in simple style of the London "
            "cat's meat!\n\n"
            "The wine was Golden Mediasch, which produces a queer sting on the tongue, "
            "which is, however, not disagreeable. I had only a couple of glasses of this, "
            "and nothing else.\n\n"
            "When I got on the coach, the driver had not taken his seat, and I saw him "
            "talking to the landlady. They were evidently talking of me, for every now "
            "and then they looked at me, and some of the people who were sitting on the "
            "bench outside the door—came and listened, and then looked at me, most of "
            "them pityingly. I could hear a lot of words all running together, but "
            "caught only one word, and that was \"Ordog\"—Satan—and \"pokol\"—hell."
        ),
    },
]

# ---------------------------------------------------------------- http

def call_model(prompt):
    if PROVIDER == "gemini":
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
        headers = {"Content-Type": "application/json", "x-goog-api-key": API_KEY}
        body = {
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {
                "maxOutputTokens": MAX_OUTPUT_TOKENS,
                "temperature": TEMPERATURE,
            },
        }
    else:
        url = f"{BASE_URL.rstrip('/')}/chat/completions"
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"}
        body = {
            "model": MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": MAX_OUTPUT_TOKENS,
            "temperature": TEMPERATURE,
        }

    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), headers=headers, method="POST"
    )
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:600]
        raise SystemExit(f"\n  HTTP {e.code} from {MODEL}:\n  {detail}\n")
    latency = time.time() - started

    if PROVIDER == "gemini":
        text = (
            data.get("candidates", [{}])[0]
            .get("content", {})
            .get("parts", [{}])[0]
            .get("text", "")
        )
        usage = data.get("usageMetadata", {})
        in_tok = usage.get("promptTokenCount", 0)
        out_tok = usage.get("candidatesTokenCount", 0)
    else:
        text = data.get("choices", [{}])[0].get("message", {}).get("content", "")
        usage = data.get("usage", {})
        in_tok = usage.get("prompt_tokens", 0)
        out_tok = usage.get("completion_tokens", 0)

    return text.strip(), in_tok, out_tok, latency


# ---------------------------------------------------------------- run

def cost_line(in_tok, out_tok):
    price = PRICES.get(MODEL)
    if not price:
        return "(no price entry for this model)"
    pin, pout = price
    usd = (in_tok * pin + out_tok * pout) / 1_000_000
    return f"~${usd:.5f}/recap  (${usd * 1000:.3f} per 1,000)"


def main():
    if not API_KEY:
        raise SystemExit("Set RECAP_API_KEY in the environment first.")

    only = None
    if "--case" in sys.argv:
        only = int(sys.argv[sys.argv.index("--case") + 1])

    selected = [c for c in CASES if only is None or c["name"].startswith(f"{only}.")]
    if not selected:
        raise SystemExit(f"No case numbered {only}")

    print(f"\n{'=' * 78}")
    print(f"  Recap spike — provider={PROVIDER}  model={MODEL}")
    print(f"  endpoint={BASE_URL if PROVIDER != 'gemini' else 'generativelanguage.googleapis.com'}")
    print(f"{'=' * 78}")

    total_in = total_out = 0

    for case in selected:
        prompt = build_prompt(
            case["book"], case["chapters"], case["last"], case["excerpt"]
        )
        print(f"\n{'-' * 78}\n  {case['name']}")
        print(f"  {case['book']} · {', '.join(case['chapters'])}")
        print(f"  excerpt: {len(case['excerpt'])} chars")
        print(f"{'-' * 78}")

        text, in_tok, out_tok, latency = call_model(prompt)
        total_in += in_tok
        total_out += out_tok

        print(f"\n  >>> {text}\n")
        print(
            f"  {in_tok} in / {out_tok} out tokens · {latency:.2f}s · {cost_line(in_tok, out_tok)}"
        )

        # cheap quality signals
        flags = []
        low = text.lower()
        if any(low.startswith(p) for p in ("in this passage", "the passage", "this passage")):
            flags.append("starts with forbidden preamble")
        if len(text.split()) > 90:
            flags.append("longer than 3 sentences")
        if not text:
            flags.append("EMPTY")
        if flags:
            print(f"  ⚠ {', '.join(flags)}")

    usd = (total_in * PRICES.get(MODEL, (0, 0))[0]
           + total_out * PRICES.get(MODEL, (0, 0))[1]) / 1_000_000
    print(f"\n{'=' * 78}")
    print(f"  TOTAL  {total_in} in / {total_out} out tokens")
    if MODEL in PRICES:
        print(f"  ~${usd:.5f} for {len(selected)} recaps — ${usd / len(selected):.5f} each")
        print(f"  at 300k recaps/month: ~${usd / len(selected) * 300_000:,.0f}/month")
    print(f"{'=' * 78}")

    print("""
  What to judge (pass/fail, subjectively):
    [ ] Is it actually about THIS passage, or generic filler?
    [ ] Does it invent plot points that are not in the text?   <- the big one
    [ ] Does it read like prose a reader would want to see?
    [ ] Is it 2-3 sentences?
    [ ] Does "you" feel natural or forced?

  If it invents things, tighten the prompt before touching any Kotlin.
""")


if __name__ == "__main__":
    main()
