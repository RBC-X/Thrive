#!/usr/bin/env python3
"""Thrive coupon image enrichment.

Fetches a REAL, product-matching photo for every coupon from Open Food Facts
(free, no API key) and bakes `imageUrl` into app/src/main/assets/data/coupons.json.
Coupons with no reliable match keep a null imageUrl, and the app falls back to
a clean category tile — never a random/stock photo.

Usage:
    python backend/enrich_coupon_images.py            # full run (all coupons)
    python backend/enrich_coupon_images.py --dry      # report only, no write

Output:
    - prints per-coupon match status
    - writes coupons.json back with imageUrl set where a photo was verified
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COUPONS_PATH = ROOT / "app" / "src" / "main" / "assets" / "data" / "coupons.json"

UA = "Thrive/1.3 (family savings app; contact@example.com)"
OFF_BASES = ["https://us.openfoodfacts.org", "https://world.openfoodfacts.org"]
PAGE_SIZE = 3
# Only accept a photo when its product name shares a meaningful token with the
# coupon, so "organic strawberries" never matches a jar of pickles.
MIN_SHARED_TOKENS = 1
STOPWORDS = {"organic", "fresh", "large", "small", "bunch", "loaf", "pack", "bag", "ct", "lb", "oz", "gallon", "dozen", "count", "each", "grade", "a", "the", "of", "and", "trail", "box", "can", "jar", "bottle", "size", "value", "great", "best", "with", "for", "in", "on", "or", "new", "old"}


def clean_tokens(s):
    words = re.sub(r"[^a-z0-9 ]", " ", str(s or "").lower()).split()
    return {w for w in words if len(w) > 1 and w not in STOPWORDS}


def meaning_tokens(s):
    """Tokens that carry product identity — numbers (sizes, volume nos.) never match."""
    return {w for w in clean_tokens(s) if not w.isdigit()}


def off_healthy():
    """Quick probe — OFF search 503s intermittently; skip it when down."""
    try:
        req = urllib.request.Request(
            OFF_BASES[0] + "/cgi/search.pl?search_terms=eggs&json=1&page_size=1&fields=product_name",
            headers={"User-Agent": UA},
        )
        with urllib.request.urlopen(req, timeout=12) as r:
            body = r.read(200000).decode("utf-8", "replace")
            return body.lstrip().startswith(("{", "["))
    except Exception:
        return False


def commons_photo(query, coupon_tokens):
    """Fallback source: Wikimedia Commons (free, no key, reliable).
    Returns (image_url, title) or (None, None)."""
    q = urllib.parse.quote_plus(query)
    url = (
        "https://commons.wikimedia.org/w/api.php?action=query&generator=search"
        f"&gsrsearch={q}&gsrnamespace=6&gsrlimit=8"
        "&prop=imageinfo&iiprop=url&iiurlwidth=480&format=json"
    )
    try:
        data = fetch_json(url, retries=2)
    except Exception:
        return None, None
    best, best_score = None, 0
    for p in data.get("query", {}).get("pages", {}).values():
        ii = p.get("imageinfo", [{}])[0]
        img = (ii.get("thumburl") or ii.get("url") or "").split("?")[0]
        title = p.get("title", "").replace("File:", "").strip()
        if not img.startswith("https://upload.wikimedia.org"):
            continue
        shared = coupon_tokens & meaning_tokens(title)
        # Commons titles are wordy; require 2+ meaningful shared tokens so a
        # lone generic word ("trail") or volume number can never match.
        if len(shared) < 2:
            continue
        if len(shared) > best_score:
            best, best_score = (img, title), len(shared)
    return best or (None, None)


def fetch_json(url, retries=4):
    """GET with retries + backoff — Open Food Facts 503s intermittently."""
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001 - any network hiccup retries
            last = e
            time.sleep(1.0 * (attempt + 1))
    raise last


def search_photo(query, coupon_tokens, use_off=True):
    """Returns (image_url, product_name) for the best match, or (None, None)."""
    q = urllib.parse.quote_plus(query)
    params = f"?search_terms={q}&json=1&page_size={PAGE_SIZE}&fields=product_name,image_url,brands"
    core = max(coupon_tokens, key=len, default="") if coupon_tokens else ""
    for base in OFF_BASES:
        try:
            data = fetch_json(base + "/cgi/search.pl" + params)
        except Exception:
            continue
        for p in data.get("products", []):
            img = (p.get("image_url") or "").strip()
            name = (p.get("product_name") or "").strip()
            if not img or not name:
                continue
            if not img.startswith("https://"):
                continue
            # Match on any shared meaningful token, or the coupon's longest
            # token appearing in the product name (handles brand prefixes).
            # Numbers (sizes) are excluded — they never establish identity.
            tokens = meaning_tokens(name)
            if (coupon_tokens & tokens) or (core and core in name.lower()):
                return img, name
        break  # first mirror that answered is authoritative for this query
    if not use_off:
        return commons_photo(query, coupon_tokens)
    # Fallback: Wikimedia Commons (stable, keyless)
    return commons_photo(query, coupon_tokens)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true", help="report only, don't write")
    ap.add_argument("--limit", type=int, default=0, help="only first N coupons (debug)")
    ap.add_argument("--offset", type=int, default=0, help="start at index N (resume/chunking)")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    data = json.loads(COUPONS_PATH.read_text(encoding="utf-8"))
    coupons = data if isinstance(data, list) else data.get("coupons", [])
    total = len(coupons)
    matched = 0
    failed = 0
    t0 = time.time()

    def _save():
        out = coupons if isinstance(data, list) else data
        COUPONS_PATH.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    use_off = off_healthy()
    print(f"Open Food Facts search: {'healthy' if use_off else 'DOWN — using Wikimedia Commons fallback'}")

    for i, c in enumerate(coupons):
        if i < args.offset:
            continue
        if args.limit and i >= args.offset + args.limit:
            break
        if c.get("imageUrl"):  # already matched by a previous run — resume
            continue
        tokens = meaning_tokens(c.get("title") or c.get("imageSeed") or "")
        if not tokens:
            failed += 1
            continue
        img, name = search_photo(" ".join(sorted(tokens)), tokens, use_off)
        if img:
            c["imageUrl"] = img
            matched += 1
            print(f"  ok   {c.get('id')} {c.get('title','')[:36]:38} <- {name[:40]} ({img[:44]}...)")
        else:
            c["imageUrl"] = None
            failed += 1
            print(f"  ---- {c.get('id')} {c.get('title','')[:36]:38} no reliable match (keeps category tile)")
        if not args.dry:
            _save()  # incremental — timeouts never lose matched progress
        time.sleep(0.15)  # be polite to the free API

    print(f"\n{matched}/{total} coupons got a real product photo, {failed} keep the clean fallback tile ({time.time()-t0:.0f}s)")

    if args.dry:
        print("(dry run — coupons.json not written)")
        return

    print(f"wrote {COUPONS_PATH}")


if __name__ == "__main__":
    main()
