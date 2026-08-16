#!/usr/bin/env python3
"""Upgrade coupon search URLs to verified direct product links.

For every bundled coupon whose title genuinely matches an Open Food Facts
product (ALL significant tokens present in the OFF product name AND a real
product photo exists), this sets:
  url         = https://world.openfoodfacts.org/product/<barcode>   (direct page)
  urlVerified = True
  imageUrl    = the OFF front image (real product photo)
  brand       = the OFF brand

Coupons that do NOT resolve keep their honest store search URL and
urlVerified=False — the app hides those from the feed ("direct link or
don't show"). OFF is free, keyless, and its product pages are real.

Usage:
    python tools/resolve_direct_links.py            # full run
    python tools/resolve_direct_links.py --dry      # report only
    python tools/resolve_direct_links.py --offset N --limit M   # chunk/resume
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

UA = "Thrive/1.4 (family savings app; contact@example.com)"
OFF_BASES = ["https://us.openfoodfacts.org", "https://world.openfoodfacts.org"]
PAGE_SIZE = 5

SIZE_WORDS = {
    "1", "2", "3", "4", "5", "6", "10", "12", "14", "15", "16", "18", "20", "24",
    "28", "32", "38", "40", "42", "45", "48", "60", "64", "150",
    "lb", "lbs", "oz", "ct", "pack", "packs", "g", "kg", "gal", "floz", "ea",
    "each", "roll", "count", "bottle", "jar", "can", "box", "bag", "gallon",
    "ounces", "pound", "pounds", "ounce", "packet", "tub", "1lb", "2lb", "3lb",
    "5lb", "16oz", "18ct", "12ct", "10ct", "6ct", "4ct", "2ct", "3ct", "40ct",
    "150ct", "2-pack", "6-roll", "bunch", "loaf", "dozen", "grade",
}
STOPWORDS = {
    "organic", "fresh", "large", "small", "best", "great", "value", "new",
    "with", "for", "in", "on", "or", "and", "a", "the", "of", "size", "old",
    "trail", "regular", "jumbo", "fancy", "whole", "extra",
}


def tokens(s):
    out = []
    for t in re.findall(r"[a-z0-9]+", (s or "").lower()):
        if t in SIZE_WORDS or t in STOPWORDS or len(t) < 3:
            continue
        out.append(t)
    return out


def fetch_json(url):
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.0 * (attempt + 1))
    raise last


def resolve(title, brand_hint=None):
    """Returns (product_url, image_url, off_brand) or None when no genuine match."""
    qt = tokens(title)
    if not qt:
        return None
    q = " ".join(qt)
    params = urllib.parse.urlencode({
        "search_terms": q, "json": 1, "page_size": PAGE_SIZE,
        "fields": "product_name,code,image_front_url,brands"})
    for base in OFF_BASES:
        try:
            data = fetch_json(base + "/cgi/search.pl?" + params)
        except Exception:
            continue
        for p in data.get("products", []):
            pn = p.get("product_name") or ""
            img = p.get("image_front_url") or ""
            code = p.get("code") or ""
            brands = p.get("brands") or ""
            if not pn or not img or not code:
                continue
            # ALL significant tokens must appear in the OFF product name —
            # a shared word like "organic" is never enough.
            pw = set(re.findall(r"[a-z0-9]+", pn.lower()))
            if not all(t in pw for t in qt):
                continue
            # Brand hint bonus: prefer the same brand when offered.
            if brand_hint and brand_hint.lower() in brands.lower():
                return (f"https://world.openfoodfacts.org/product/{code}", img, brands.split(",")[0].strip())
            return (f"https://world.openfoodfacts.org/product/{code}", img, brands.split(",")[0].strip() or None)
        break  # first mirror that answered is authoritative
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--offset", type=int, default=0)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    coupons = json.loads(COUPONS_PATH.read_text(encoding="utf-8"))
    total = len(coupons)
    upgraded = 0
    already = 0
    start = args.offset
    end = total if args.limit <= 0 else min(total, args.offset + args.limit)
    t0 = time.time()

    for i in range(start, end):
        c = coupons[i]
        if c.get("urlVerified"):
            already += 1
            continue
        title = c.get("title") or ""
        brand = c.get("brand") or None
        r = resolve(title, brand)
        if r:
            url, img, b = r
            print(f"HIT  [{c['id']}] {title} -> {url} | {img.split('/')[-1]}")
            upgraded += 1
            if not args.dry:
                c["url"] = url
                c["urlVerified"] = True
                c["estimated"] = False
                c["imageUrl"] = img
                if b:
                    c["brand"] = b
        else:
            print(f"MISS [{c['id']}] {title}")
        time.sleep(0.25)

    elapsed = time.time() - t0
    print(f"--- [{start}:{end}] {upgraded} upgraded, {already} already verified, "
          f"{end - start - upgraded - already} unresolved | {elapsed:.0f}s")
    if not args.dry:
        COUPONS_PATH.write_text(json.dumps(coupons, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {COUPONS_PATH}")


if __name__ == "__main__":
    main()
