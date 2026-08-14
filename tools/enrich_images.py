#!/usr/bin/env python3
"""Match Thrive deals/coupons to real Open Food Facts products (real photos).

Dry-run mode: prints a reviewable table of candidates without writing.
Apply mode: writes imageUrl + brand + estimated flags into the JSON files.

Matching rules (honesty over coverage):
  - query = product name minus size words (1, 2 lb, 16 oz, ct, pack, etc.)
  - significant tokens: lowercase word-boundary alnum tokens, len>=3, not size words
  - candidate accepted only if EVERY significant token appears (word-boundary,
    stemmed: s/es/ies) in the OFF product_name AND an image exists
  - reject when the OFF product_name looks like a different head noun
    (requires same token count within tolerance to avoid "banana" yogurt)
"""
import json, re, sys, urllib.request, urllib.parse, time

OFF_SEARCH = "https://world.openfoodfacts.org/cgi/search.pl"

SIZE_WORDS = {"1","2","3","4","5","6","10","12","14","15","16","18","20","24","28","32","38","40","42","45","48","60","64","150",
              "lb","lbs","oz","ct","pack","packs","g","kg","gal","floz","ea","each","roll","count","bottle","jar","can","box","bag",
              "1lb","2lb","3lb","5lb","1.5lb","16oz","18ct","12ct","10ct","6ct","4ct","2ct","3ct","40ct","150ct","2-pack","6-roll",
              "gallon","ounces","pound","pounds","ounce","packet","tub"}

def toks(s):
    out = []
    for t in re.findall(r"[a-z0-9]+", (s or "").lower()):
        if t in SIZE_WORDS or len(t) < 3:
            continue
        out.append(t)
    return out

def stem(t):
    if t.endswith("ies") and len(t) > 4: return t[:-3] + "y"
    if t.endswith("es") and len(t) > 3: return t[:-2]
    if t.endswith("s") and len(t) > 3: return t[:-1]
    return t

def words(s):
    return set(re.findall(r"[a-z0-9]+", (s or "").lower()))

def matches(qtokens, prod_name):
    pw = words(prod_name)
    pstems = {stem(w) for w in pw}
    for t in qtokens:
        if stem(t) not in pstems:
            return False
    return True

def search(q, n=8):
    url = OFF_SEARCH + "?" + urllib.parse.urlencode({
        "search_terms": q, "json": 1, "page_size": n,
        "fields": "product_name,brands,image_front_url,code,quantity"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=12) as r:
                return json.load(r).get("products", [])
        except Exception:
            time.sleep(1.5)
    return []

# Category tags that mean "a different product than the query implies" even
# when the name shares a token (coconut milk for milk, onion Pringles for onions,
# bacon-flavored crackers for bacon, plant-based chicken for chicken thighs).
DISTRACTOR_CATS = {
    "en:coconut-milks", "en:plant-based-milks", "en:almond-milks", "en:soy-milks",
    "en:oat-milks", "en:rice-milks", "en:potato-crisps", "en:crisps", "en:chips",
    "en:crackers", "en:cookies", "en:chocolates", "en:candies", "en:confectioneries",
    "en:instant-noodles", "en:ramen", "en:plant-based-meats", "en:meat-substitutes",
    "en:breaded-products", "en:breaded-fishes", "en:breaded-chicken",
    "en:ice-creams", "en:ice-cream", "en:desserts", "en:yogurts", "en:yogurt",
    "en:breakfast-cereals", "en:prepared-salads", "en:pet-foods", "en:snacks",
}

# Curated, barcode-verified real product photos (checked via the OFF product API).
# id -> OFF barcode. Only products confirmed to be the actual item.
OVERRIDES = {
    "dl03": "5000326010030",  # Large Eggs
    "dl04": "5000326010030",
    "dl05": "5054775188321",  # Unsalted Butter
    "dl06": "0096619221073",  # Shredded Cheddar
    "dl09": "0023700162205",  # Boneless Chicken Breast
    "dl12": "0022655715610",  # Ground Turkey
    "dl34": "8076800195057",  # Spaghetti
    "dl36": "5057373748423",  # Black Beans
    "dl37": "4099100117882",  # Crushed Tomatoes
    "dl55": "8715035110809",  # Soy Sauce
    "dl56": "87157215",       # Ketchup
    "dl57": "6111184004716",  # Mustard
    "dl62": "0078742350745",  # Trash Bags
    "c37": "0019200878708",   # Lysol Disinfectant Spray
}

# Deals that are generic produce/meat (no single authentic package photo):
# leave them with the clean fallback tile rather than a mismatched photo.
FALLBACK_ONLY = {
    "dl01", "dl02", "dl10", "dl11", "dl13", "dl14", "dl15", "dl16", "dl17", "dl18",
    "dl20", "dl21", "dl22", "dl24", "dl25", "dl26", "dl27", "dl28", "dl29",
    "dl30", "dl31", "dl32", "dl33", "dl35", "dl38", "dl39", "dl40", "dl41",
    "dl42", "dl43", "dl44", "dl45", "dl46", "dl47", "dl48", "dl49", "dl50",
    "dl51", "dl52", "dl53", "dl54", "dl58", "dl59", "dl60", "dl61", "dl63",
    "dl64", "dl65", "dl66",
}

_CAT_CACHE = {}

def product_by_code(code):
    if code in _CAT_CACHE:
        return _CAT_CACHE[code]
    try:
        with urllib.request.urlopen(f"https://world.openfoodfacts.org/api/v2/product/{code}.json", timeout=12) as r:
            d = json.load(r)
    except Exception:
        d = {}
    p = d.get("product") or {}
    _CAT_CACHE[code] = p
    return p

def best_for(name, brand_hint=None):
    qt = toks(name)
    if not qt:
        return None
    q = " ".join(qt)
    prods = search(q)
    scored = []
    for p in prods:
        pn = p.get("product_name") or ""
        img = p.get("image_front_url") or ""
        if not img or not matches(qt, pn):
            continue
        pt = toks(pn)
        # length proximity: reject when the product name has a very different
        # number of significant tokens (catches "Yogurt Bnine BANANA" for banana)
        if abs(len(pt) - len(qt)) > 2 and not (brand_hint and brand_hint.lower() in pn.lower()):
            continue
        # category distractor check
        cats = set(p.get("categories_tags") or [])
        if cats & DISTRACTOR_CATS:
            continue
        # brand hint bonus
        bonus = 1.0 if (brand_hint and brand_hint.lower() in (p.get("brands") or "").lower()) else 0.0
        # prefer exact-token-count proximity
        score = len(qt) / max(1, len(pt)) + bonus
        scored.append((score, pn, p.get("brands"), img, p.get("code")))
    scored.sort(key=lambda x: -x[0])
    return scored[0] if scored else None

def main():
    apply = "--apply" in sys.argv
    for rel in ["deals.json", "coupons.json"]:
        path = "app/src/main/assets/data/" + rel
        data = json.load(open(path, encoding="utf-8"))
        hits, misses = 0, 0
        for entry in data:
            eid = entry.get("id", "")
            name = entry.get("productName") or entry.get("title") or ""
            brand = entry.get("brand")
            if entry.get("imageUrl"):
                continue
            # curated verified override first
            code = OVERRIDES.get(eid)
            if code:
                p = product_by_code(code)
                img = p.get("image_front_url") or ""
                pn = p.get("product_name") or name
                brands = p.get("brands") or ""
                if img:
                    print(f"OVR  [{rel}] {name} -> {pn} | {brands.split(',')[0] if brands else ''} | {img.split('/')[-1]}")
                    hits += 1
                    if apply:
                        entry["imageUrl"] = img
                        if brands:
                            entry["brand"] = brands.split(",")[0].strip()
                    continue
            if eid in FALLBACK_ONLY:
                print(f"FALL [{rel}] {name}")
                misses += 1
                continue
            b = best_for(name, brand)
            if b:
                score, pn, brands, img, code = b
                print(f"HIT  [{rel}] {name} -> {pn} | {brands} | {img.split('/')[-1]} | {code}")
                hits += 1
                if apply:
                    entry["imageUrl"] = img
                    if brands:
                        entry["brand"] = brands.split(",")[0].strip()
            else:
                print(f"MISS [{rel}] {name}")
                misses += 1
            time.sleep(0.4)
        print(f"--- {rel}: {hits} hits, {misses} misses")
        if apply:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=1, ensure_ascii=False)
                f.write("\n")
            print(f"wrote {path}")

if __name__ == "__main__":
    main()
