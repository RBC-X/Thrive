#!/usr/bin/env python3
"""Thrive store-logo enrichment (v4).

Authoritative approach: a curated map of store -> Commons logo filename
(verified against the Wikipedia media-list / summary APIs in prior runs),
served via Special:FilePath so no hash-path math is needed. Stores without a
curated entry are looked up slowly via the media-list API with backoff.

Fallback chain in the app: product photo -> store logo -> category tile.

Usage:
    python backend/enrich_store_logos.py            # fetch + write
    python backend/enrich_store_logos.py --dry      # report only
    python backend/enrich_store_logos.py --verify   # slow spaced HEAD check
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
MEDIA_API = "https://en.wikipedia.org/api/rest_v1/page/media-list/"
WIDTH = 250

# Store -> Commons filename of the CURRENT official logo (verified earlier).
CURATED = {
    "Walmart": "Walmart_logo_(2008).svg",
    "Kroger": "Kroger_(2021)_logo.svg",
    "Target": "Target_Corporation_logo_(vector).svg",
    "Aldi": "AldiWorldwideLogo.svg",
    "Trader Joe's": "Trader Joe's_logo.svg",
    "Costco": "Costco_Wholesale_logo_2010-10-26.svg",
    "Whole Foods": "Whole_Foods_Market_201x_logo.svg",
    "Sam's Club": "Sam's_Club_logo.svg",
    "Dollar General": "Dollar_General_logo.svg",
    "Dollar Tree": "Dollar_Tree_logo.svg",
    "Chipotle": "Chipotle_Mexican_Grill_logo.svg",
    "Panera": "Panera_Bread_logo.svg",
    "Subway": "Subway_2016_logo.svg",
    "Starbucks": "Starbucks_Coffee_Logo.svg",
    "McDonald's": "McDonald's_logo.svg",
    "Pizza Hut": "Pizza_Hut_logo.svg",
    "Domino's": "Dominos_pizza_logo.svg",
    "Olive Garden": "Olive_Garden_Logo.svg",
    "Chick-fil-A": "Chick-fil-A_Logo.svg",
    "Taco Bell": "Taco_Bell_logo.svg",
    "CVS": "CVS_Health_logo.svg",
    "Walgreens": "Walgreens_2020_primary_logo.svg",
    "Amazon": "Amazon_logo.svg",
    "Sephora": "Sephora_logo.svg",
    "Ulta": "Ulta_Beauty_logo.svg",
    "Home Depot": "The_Home_Depot_logo.svg",
    "Lowe's": "Lowes_Companies_Logo.svg",
    "IKEA": "Ikea_logo.svg",
    "Harbor Freight": "Harbor_Freight_Tools_logo.svg",
    "Best Buy": "Best_Buy_logo_2018.svg",
    "Apple": "Apple_logo_black.svg",
    "Newegg": "Newegg_logo.svg",
    "eBay": "EBay_logo.svg",
    "Staples": "Staples,_Inc._logo.svg",
    "Office Depot": "Office_Depot_logo.svg",
}

ARTICLE = {
    "Walmart": "Walmart",
    "Kroger": "Kroger",
    "Target": "Target Corporation",
    "Aldi": "Aldi",
    "Trader Joe's": "Trader Joe's",
    "Costco": "Costco",
    "Whole Foods": "Whole Foods Market",
    "Sam's Club": "Sam's Club",
    "Dollar General": "Dollar General",
    "Dollar Tree": "Dollar Tree",
    "Chipotle": "Chipotle Mexican Grill",
    "Panera": "Panera Bread",
    "Subway": "Subway (restaurant)",
    "Starbucks": "Starbucks",
    "McDonald's": "McDonald's",
    "Pizza Hut": "Pizza Hut",
    "Domino's": "Domino's Pizza",
    "Olive Garden": "Olive Garden",
    "Chick-fil-A": "Chick-fil-A",
    "Taco Bell": "Taco Bell",
    "CVS": "CVS Pharmacy",
    "Walgreens": "Walgreens",
    "Amazon": "Amazon (company)",
    "Sephora": "Sephora",
    "Ulta": "Ulta Beauty",
    "Home Depot": "The Home Depot",
    "Lowe's": "Lowe's",
    "IKEA": "IKEA",
    "Harbor Freight": "Harbor Freight Tools",
    "Best Buy": "Best Buy",
    "Apple": "Apple Inc.",
    "Newegg": "Newegg",
    "eBay": "eBay",
    "Staples": "Staples Inc.",
    "Office Depot": "Office Depot",
}

BAD_HINTS = ("building", "headquarters", "exterior", "storefront", "store front",
             "street", "facade", "location", "store in", "inside", "interior",
             "parking", "entrance", "outlet", "mall", "penny circulation", "map")
YEAR = re.compile(r"(19|20)\d\d")


def filepath_url(filename):
    q = urllib.parse.urlencode({"width": str(WIDTH)})
    return ("https://commons.wikimedia.org/wiki/Special:FilePath/"
            + urllib.parse.quote(filename.replace(" ", "_")) + "?" + q)


def media_list(title):
    url = MEDIA_API + urllib.parse.quote(title)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read(2000000).decode("utf-8", "replace"))


def media_list_retry(title, tries=6):
    for i in range(tries):
        try:
            return media_list(title)
        except urllib.error.HTTPError as e:
            if e.code == 429 and i < tries - 1:
                time.sleep(15 + 15 * i)
                continue
            raise
    return {}


def pick_logo_via_api(store):
    title = ARTICLE.get(store, store)
    try:
        d = media_list_retry(title)
    except Exception as e:
        return None, f"media-list failed: {e}"
    cands = []
    for it in d.get("items", []):
        srcset = it.get("srcset") or []
        src = (srcset[0].get("src") if srcset else None) or it.get("src", "")
        fname = it.get("title", "").replace("File:", "")
        if not src or not fname:
            continue
        t = fname.lower()
        if not t.endswith((".svg", ".png", ".jpg", ".jpeg")):
            continue
        if any(b in t for b in BAD_HINTS):
            continue
        if "logo" not in t and "wordmark" not in t:
            continue
        age = 0
        years = YEAR.findall(fname)
        if years:
            try:
                y = max(int(x) for x in years)
                age = max(0, (2024 - y) * 2)
            except Exception:
                pass
        if any(w in t for w in ("old_", "old-", "early", "historical", "former", "classic", "vintage", "1961", "1939")):
            age += 10
        sk = store.lower().replace("'", "").replace("&", "").replace(" ", "")
        name_ok = sk in t.replace("'", "").replace("&", "").replace(" ", "")
        cands.append((age + (0 if name_ok else 8), fname))
    cands.sort(key=lambda c: c[0])
    if cands:
        return filepath_url(cands[0][1]), cands[0][1]
    return None, "no logo image on article"


def head_ok(url, timeout=20):
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return 200 <= r.status < 400
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    coupons = json.loads(COUPONS_PATH.read_text(encoding="utf-8"))
    stores = sorted({c.get("store", "?") for c in coupons})

    if args.verify:
        bad = ok = 0
        for c in coupons:
            url = c.get("storeLogoUrl")
            if not url:
                continue
            if head_ok(url):
                ok += 1
            else:
                bad += 1
                print(f"  DEAD {c['store']}: {url}")
            time.sleep(1.5)
        print(f"verify: {bad} dead, {ok} alive of {ok + bad} baked")
        return 0 if bad == 0 else 1

    found = {}
    for store in stores:
        curated = CURATED.get(store)
        if curated:
            found[store] = filepath_url(curated)
            print(f"OK   {store}: curated {curated}")
            continue
        url, how = pick_logo_via_api(store)
        found[store] = url
        print(f"{'OK ' if url else 'MISS'} {store}: {how}")
        time.sleep(1.0)

    if args.dry:
        return 0

    n = 0
    for c in coupons:
        url = found.get(c.get("store"))
        if url and c.get("storeLogoUrl") != url:
            c["storeLogoUrl"] = url
            n += 1
    COUPONS_PATH.write_text(json.dumps(coupons, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"\nwrote storeLogoUrl to {n} coupons ({len(found)} stores covered)")


if __name__ == "__main__":
    sys.exit(main())
