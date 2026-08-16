#!/usr/bin/env python3
"""Parallel resolver: upgrade coupon search URLs to OFF product pages.

Splits the catalog across N worker threads (OFF tolerates modest concurrency),
saves incrementally every 50 items so progress survives interruptions, and
only upgrades a coupon when ALL its significant title tokens appear in the OFF
product name AND a real front image exists.
"""
import json, re, sys, time, threading, urllib.parse, urllib.request
from pathlib import Path

COUPONS_PATH = Path(__file__).resolve().parents[1] / "app/src/main/assets/data/coupons.json"
UA = "Thrive/1.4 (family savings app; contact@example.com)"
OFF_BASES = ["https://us.openfoodfacts.org", "https://world.openfoodfacts.org"]
PAGE_SIZE = 5
SIZE_WORDS = {"1","2","3","4","5","6","10","12","14","15","16","18","20","24","28","32","38","40","42","45","48","60","64","150","lb","lbs","oz","ct","pack","packs","g","kg","gal","floz","ea","each","roll","count","bottle","jar","can","box","bag","gallon","ounces","pound","pounds","ounce","packet","tub","1lb","2lb","3lb","5lb","16oz","18ct","12ct","10ct","6ct","4ct","2ct","3ct","40ct","150ct","2-pack","6-roll","bunch","loaf","dozen","grade"}
STOPWORDS = {"organic","fresh","large","small","best","great","value","new","with","for","in","on","or","and","a","the","of","size","old","trail","regular","jumbo","fancy","whole","extra","does","not","artificially","flavored"}

def tokens(s):
    return [t for t in re.findall(r"[a-z0-9]+", (s or "").lower()) if t not in SIZE_WORDS and t not in STOPWORDS and len(t) >= 3]

def fetch_json(url):
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.load(r)
        except Exception as e:
            last = e
            time.sleep(0.8 * (attempt + 1))
    raise last

def resolve(title):
    qt = tokens(title)
    if not qt:
        return None
    q = " ".join(qt)
    params = urllib.parse.urlencode({"search_terms": q, "json": 1, "page_size": PAGE_SIZE, "fields": "product_name,code,image_front_url,brands"})
    for base in OFF_BASES:
        try:
            data = fetch_json(base + "/cgi/search.pl?" + params)
        except Exception:
            continue
        for p in data.get("products", []):
            pn = p.get("product_name") or ""
            img = p.get("image_front_url") or ""
            code = p.get("code") or ""
            if not pn or not img or not code:
                continue
            pw = set(re.findall(r"[a-z0-9]+", pn.lower()))
            if not all(t in pw for t in qt):
                continue
            return (f"https://world.openfoodfacts.org/product/{code}", img)
        break
    return None

def worker(coupons, index, results, lock, progress, save_lock):
    while True:
        with lock:
            i = index[0]
            index[0] += 1
        if i >= len(coupons):
            return
        c = coupons[i]
        if c.get("urlVerified"):
            with lock:
                results[i] = ("already", None)
                progress[0] += 1
            continue
        r = resolve(c.get("title") or "")
        if r:
            with lock:
                results[i] = ("hit", r)
        else:
            with lock:
                results[i] = ("miss", None)
        with lock:
            progress[0] += 1
        # Incremental save every 40 items so a kill never loses all work.
        with save_lock:
            if progress[0] % 40 == 0:
                save(coupons, results)
                print(f"progress {progress[0]}/{len(coupons)}", flush=True)

def save(coupons, results):
    for i, (kind, val) in enumerate(results):
        if kind == "hit":
            c = coupons[i]
            url, img = val
            c["url"] = url
            c["urlVerified"] = True
            c["estimated"] = False
            c["imageUrl"] = img
    import os as _os
    _tmp = COUPONS_PATH.with_suffix(".json.tmp")
    _tmp.write_text(json.dumps(coupons, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    _os.replace(_tmp, COUPONS_PATH)

def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    coupons = json.loads(COUPONS_PATH.read_text(encoding="utf-8"))
    results = [("todo", None)] * len(coupons)
    lock = threading.Lock()
    save_lock = threading.Lock()
    index = [0]
    progress = [0]
    threads = []
    for _ in range(16):
        t = threading.Thread(target=worker, args=(coupons, index, results, lock, progress, save_lock), daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join()
    save(coupons, results)
    verified = sum(1 for c in coupons if c.get("urlVerified"))
    print(f"DONE: {verified}/{len(coupons)} verified", flush=True)

if __name__ == "__main__":
    main()
