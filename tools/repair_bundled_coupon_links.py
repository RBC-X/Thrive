#!/usr/bin/env python3
"""Restore honest retailer destinations in the bundled coupon catalog.

Open Food Facts is a useful product-photo source, but it is not a retailer and
cannot verify a store's current price or offer. This deterministic repair keeps
those image URLs while replacing any OFF destination with the named retailer's
search/home page and marking every bundled price as an estimate.
"""

import json
from pathlib import Path
from urllib.parse import quote

CATALOG = Path(__file__).resolve().parents[1] / "app/src/main/assets/data/coupons.json"


def q(value):
    return quote(value or "")


SEARCH = {
    "Walmart": lambda s: f"https://www.walmart.com/search?q={q(s)}",
    "Target": lambda s: f"https://www.target.com/s?searchTerm={q(s)}",
    "Kroger": lambda s: f"https://www.kroger.com/search?query={q(s)}",
    "Aldi": lambda s: "https://www.aldi.us/products.html",
    "Costco": lambda s: "https://www.costco.com/",
    "Trader Joe's": lambda s: "https://www.traderjoes.com/home",
    "Whole Foods": lambda s: "https://www.wholefoodsmarket.com/",
    "Sam's Club": lambda s: "https://www.samsclub.com/",
    "Publix": lambda s: f"https://www.publix.com/search?q={q(s)}",
    "H-E-B": lambda s: f"https://www.heb.com/search?q={q(s)}",
    "Safeway": lambda s: f"https://www.safeway.com/shop/search-results.html?q={q(s)}",
    "Albertsons": lambda s: f"https://www.albertsons.com/shop/search-results.html?q={q(s)}",
    "Wegmans": lambda s: f"https://shop.wegmans.com/search?search_term={q(s)}",
    "Food Lion": lambda s: f"https://www.foodlion.com/search/?q={q(s)}",
    "Meijer": lambda s: f"https://www.meijer.com/shopping/search.html?q={q(s)}",
    "Giant": lambda s: f"https://giantfood.com/search/?q={q(s)}",
    "Stop & Shop": lambda s: f"https://stopandshop.com/search/?q={q(s)}",
    "Winn-Dixie": lambda s: f"https://www.winndixie.com/search/?q={q(s)}",
    "Dollar General": lambda s: f"https://www.dollargeneral.com/search?q={q(s)}",
    "Dollar Tree": lambda s: "https://www.dollartree.com/",
    "Chipotle": lambda s: "https://www.chipotle.com/",
    "Starbucks": lambda s: "https://www.starbucks.com/menu",
    "Pizza Hut": lambda s: "https://www.pizzahut.com/",
    "Ulta": lambda s: f"https://www.ulta.com/search?Ntt={q(s)}",
    "Apple": lambda s: f"https://www.apple.com/us/shop/goto/search?q={q(s)}",
}


def main():
    coupons = json.loads(CATALOG.read_text(encoding="utf-8"))
    repaired = 0
    for coupon in coupons:
        coupon["estimated"] = True
        if "openfoodfacts.org" not in (coupon.get("url") or ""):
            continue
        make_url = SEARCH.get(coupon.get("store"))
        if make_url is None:
            raise SystemExit(f"No retailer destination for {coupon.get('store')!r}")
        coupon["url"] = make_url(coupon.get("title"))
        coupon["urlVerified"] = False
        repaired += 1
    CATALOG.write_text(json.dumps(coupons, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Repaired {repaired} third-party destinations; all {len(coupons)} bundled prices are estimated.")


if __name__ == "__main__":
    main()
