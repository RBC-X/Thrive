#!/usr/bin/env python3
"""Thrive recipe-image enrichment.

For every recipe in recipes.json, find a real, free food photo on Wikimedia
Commons (no API key) whose filename shares meaningful tokens with the dish
name, and bake `imageUrl`. Recipes with no reliable match keep null so the
app falls back to the clean food tile — never a random photo.

Usage:
    python backend/enrich_recipe_images.py            # fetch + write
    python backend/enrich_recipe_images.py --dry      # report only
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECIPES_PATH = ROOT / "app" / "src" / "main" / "assets" / "data" / "recipes.json"

UA = "Thrive/1.3 (family savings app; contact@example.com)"
API = "https://commons.wikimedia.org/w/api.php"

STOPWORDS = {
    "a", "an", "the", "and", "with", "for", "in", "on", "of", "or", "to", "from",
    "fresh", "homemade", "easy", "quick", "simple", "family", "style", "cheesy",
    "creamy", "savory", "hearty", "golden", "baked", "fried", "grilled", "roasted",
    "slow", "cooker", "instant", "pot", "pan", "skillet", "sheet", "one", "two",
    "classic", "best", "ultimate", "weeknight", "dinner", "lunch", "breakfast",
    "bowl", "wrap", "soup", "salad", "sauce", "dish", "meal", "recipe",
}


def clean_tokens(s):
    words = re.sub(r"[^a-z0-9 ]", " ", str(s or "").lower()).split()
    return {w for w in words if len(w) > 1 and w not in STOPWORDS}


def commons_search(query, limit=8):
    params = {
        "action": "query",
        "generator": "search",
        "gsrsearch": query,
        "gsrnamespace": "6",
        "gsrlimit": str(limit),
        "prop": "imageinfo",
        "iiprop": "url|mime",
        "iiurlwidth": "500",
        "format": "json",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read(400000).decode("utf-8", "replace"))


def pick_photo(name):
    """Find a Commons food photo whose filename shares 2+ meaningful tokens."""
    tokens = clean_tokens(name)
    if not tokens:
        return None
    for q in (name, " ".join(tokens)):
        for attempt in range(4):
            try:
                d = commons_search(q)
                break
            except urllib.error.HTTPError as e:
                if e.code == 429 and attempt < 3:
                    time.sleep(10 + 10 * attempt)
                    continue
                print(f"    [search failed: {e}]", file=sys.stderr)
                return None
        cands = []
        for p in (d.get("query", {}).get("pages", {}) or {}).values():
            ii = (p.get("imageinfo") or [{}])[0]
            title = p.get("title", "File:").replace("File:", "")
            mime = ii.get("mime", "")
            if mime not in ("image/png", "image/jpeg", "image/webp"):
                continue
            thumb = ii.get("thumburl") or ii.get("url")
            if not thumb or not thumb.startswith("https://"):
                continue
            ft = clean_tokens(title)
            shared = tokens & ft
            if len(shared) >= 2:
                cands.append((len(shared), title, thumb))
        if cands:
            cands.sort(key=lambda c: c[0], reverse=True)
            return cands[0][2]
        time.sleep(0.8)
    return None


def main():
    dry = "--dry" in sys.argv
    recipes = json.loads(RECIPES_PATH.read_text(encoding="utf-8"))
    n = 0
    for r in recipes:
        if r.get("imageUrl"):
            print(f"OK   {r['name']}: already has image")
            continue
        url = pick_photo(r["name"])
        status = "OK " if url else "MISS"
        print(f"{status} {r['name']}: {url}")
        if url and not dry:
            r["imageUrl"] = url
            n += 1
        time.sleep(0.8)
    if not dry:
        RECIPES_PATH.write_text(json.dumps(recipes, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"\nwrote imageUrl to {n} recipes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
