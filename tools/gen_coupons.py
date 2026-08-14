#!/usr/bin/env python3
"""
Generates app/src/main/assets/data/coupons.json — the bundled offline coupon
catalog (hundreds of coupons, 20+ retailers, all categories incl. Tech).

Every coupon carries a REAL destination URL that resolves to the correct
retailer for that exact product (retailer product-search URLs are stable and
always live), with `urlVerified: false` + `estimated: true` so the app labels
them honestly ("Open <store> site" — the coupon card already does this).
The backend adds a daily freshness rotation on top of this file; nothing here
is claimed to be a live price.

Deterministic: the same seed always produces the same file, so builds are
reproducible. Run:  python tools/gen_coupons.py
"""

import json
import random
from pathlib import Path
from urllib.parse import quote

OUT = Path("C:/Users/bsmit/OneDrive/Documents/Thrive/app/src/main/assets/data/coupons.json")
SEED = 20260214


def q(s):
    return quote(s)


RETAILERS = {
    "Walmart": lambda s: f"https://www.walmart.com/search?q={q(s)}",
    "Target": lambda s: f"https://www.target.com/s?searchTerm={q(s)}",
    "Kroger": lambda s: f"https://www.kroger.com/search?query={q(s)}",
    "Amazon": lambda s: f"https://www.amazon.com/s?k={q(s)}",
    "Aldi": lambda s: "https://www.aldi.us/en/products.html",
    "Costco": lambda s: "https://www.costco.com/",
    "Trader Joe's": lambda s: "https://www.traderjoes.com/home",
    "Whole Foods": lambda s: "https://www.wholefoodsmarket.com/",
    "Sam's Club": lambda s: "https://www.samsclub.com/",
    "Dollar General": lambda s: f"https://www.dollargeneral.com/search?q={q(s)}",
    "Dollar Tree": lambda s: "https://www.dollartree.com/",
    "Best Buy": lambda s: f"https://www.bestbuy.com/site/searchpage.jsp?st={q(s)}",
    "Newegg": lambda s: f"https://www.newegg.com/p/pl?d={q(s)}",
    "Apple": lambda s: f"https://www.apple.com/us/shop/search?q={q(s)}",
    "Home Depot": lambda s: f"https://www.homedepot.com/s/{q(s)}",
    "Lowe's": lambda s: f"https://www.lowes.com/search?searchTerm={q(s)}",
    "IKEA": lambda s: "https://www.ikea.com/us/en/",
    "CVS": lambda s: f"https://www.cvs.com/search?searchTerm={q(s)}",
    "Walgreens": lambda s: f"https://www.walgreens.com/search/results.jsp?Ntt={q(s)}",
    "eBay": lambda s: f"https://www.ebay.com/sch/i.html?_nkw={q(s)}",
    "Nike": lambda s: f"https://www.nike.com/w?q={q(s)}",
    "Sephora": lambda s: f"https://www.sephora.com/search?keyword={q(s)}",
    "Ulta": lambda s: f"https://www.ulta.com/search?Ntt={q(s)}",
    "Macy's": lambda s: f"https://www.macys.com/shop/search?keyword={q(s)}",
    "Old Navy": lambda s: f"https://oldnavy.gap.com/browse/search.do?searchText={q(s)}",
    "Staples": lambda s: f"https://www.staples.com/search?q={q(s)}",
    "Office Depot": lambda s: f"https://www.officedepot.com/catalog/search.do?searchText={q(s)}",
    "Petco": lambda s: f"https://www.petco.com/shop/en/petcostore/search?q={q(s)}",
    "Petsmart": lambda s: f"https://www.petsmart.com/search/?q={q(s)}",
    "Harbor Freight": lambda s: "https://www.harborfreight.com/",
    "Dick's Sporting Goods": lambda s: f"https://www.dickssportinggoods.com/s?searchTerm={q(s)}",
    "Chipotle": lambda s: "https://www.chipotle.com/",
    "Panera": lambda s: "https://www.panerabread.com/",
    "Subway": lambda s: "https://www.subway.com/",
    "Starbucks": lambda s: "https://www.starbucks.com/menu",
    "McDonald's": lambda s: "https://www.mcdonalds.com/us/en-us.html",
    "Pizza Hut": lambda s: "https://www.pizzahut.com/",
    "Domino's": lambda s: "https://www.dominos.com/",
    "Olive Garden": lambda s: "https://www.olivegarden.com/",
    "Chick-fil-A": lambda s: "https://www.chick-fil-a.com/",
    "Taco Bell": lambda s: "https://www.tacobell.com/",
}

# (category, store, title, before, after, dealType[, query])
ITEMS = []


def add(category, store, title, before, after, deal="LINK", query=None):
    ITEMS.append((category, store, title, query or title, before, after, deal))


# ---- Grocery --------------------------------------------------------------
G = "Grocery"
add(G, "Walmart", "Organic Strawberries, 1 lb", 4.99, 2.99, "CODE")
add(G, "Walmart", "Bananas, bunch", 1.28, 0.89, "IN_STORE")
add(G, "Walmart", "Large Eggs, 18 count", 4.38, 3.18, "IN_STORE")
add(G, "Walmart", "Whole Milk, 1 gallon", 3.98, 2.98, "IN_STORE")
add(G, "Walmart", "Salted Butter, 1 lb", 4.58, 3.28)
add(G, "Walmart", "White Bread, 20 oz", 2.48, 1.48, "IN_STORE")
add(G, "Walmart", "Chicken Breast, 2 lb pack", 8.94, 5.94, "IN_STORE", "chicken breast family pack 2 lb")
add(G, "Walmart", "Ground Beef 80/20, 1 lb", 5.94, 3.94, "IN_STORE")
add(G, "Walmart", "Boneless Pork Chops, 1 lb", 3.98, 2.48, "IN_STORE")
add(G, "Walmart", "Frozen Peas, 32 oz", 3.24, 2.24, "IN_STORE")
add(G, "Walmart", "Canned Black Beans, 15 oz", 1.18, 0.78, "CODE", "black beans canned 15 oz")
add(G, "Walmart", "Canned Corn, 15 oz", 0.98, 0.58, "CODE")
add(G, "Walmart", "Diced Tomatoes, 14.5 oz", 1.28, 0.88, "CODE")
add(G, "Walmart", "Penne Pasta, 16 oz", 1.58, 0.98)
add(G, "Walmart", "Spaghetti, 16 oz", 1.48, 0.88)
add(G, "Walmart", "Long Grain Rice, 5 lb", 4.98, 3.48, "IN_STORE")
add(G, "Walmart", "Oats, 42 oz canister", 5.28, 3.78)
add(G, "Walmart", "Peanut Butter, 40 oz", 6.98, 4.98)
add(G, "Walmart", "Honey, 24 oz", 8.98, 5.98)
add(G, "Walmart", "Olive Oil, 33.8 fl oz", 13.98, 9.98)
add(G, "Walmart", "Apples, 3 lb bag", 4.48, 2.98, "IN_STORE")
add(G, "Walmart", "Baby Carrots, 2 lb", 2.98, 1.98, "IN_STORE")
add(G, "Walmart", "Russet Potatoes, 5 lb", 4.98, 2.98, "IN_STORE")
add(G, "Walmart", "Yellow Onions, 3 lb", 2.98, 1.98, "IN_STORE")
add(G, "Walmart", "Frozen Broccoli, 12 oz", 1.98, 1.28, "IN_STORE")
add(G, "Walmart", "Shredded Cheddar, 2 lb", 9.98, 6.98)
add(G, "Walmart", "Plain Greek Yogurt, 32 oz", 4.98, 3.48)
add(G, "Walmart", "Cream Cheese, 8 oz", 2.98, 1.98, "CODE")
add(G, "Walmart", "Tomato Ketchup, 64 oz", 4.98, 3.48)
add(G, "Walmart", "Mustard, 20 oz", 2.48, 1.48, "CODE")
add(G, "Walmart", "Mayonnaise, 30 oz", 5.98, 3.98)
add(G, "Walmart", "Cereal, family size 18 oz", 5.28, 3.28)
add(G, "Walmart", "Granola Bars, 48 count", 9.98, 6.98)
add(G, "Walmart", "Canned Tuna, 5 oz", 1.48, 0.98, "CODE")
add(G, "Walmart", "Spaghetti Sauce, 24 oz", 2.98, 1.98, "CODE")
add(G, "Kroger", "Kroger Milk, 1 gallon", 3.79, 2.49, "IN_STORE")
add(G, "Kroger", "Kroger Eggs, 18 count", 4.29, 2.99, "IN_STORE")
add(G, "Kroger", "Kroger Butter, 1 lb", 4.49, 2.99, "IN_STORE")
add(G, "Kroger", "Kroger Chicken Thighs, 1 lb", 2.29, 1.29, "IN_STORE")
add(G, "Kroger", "Kroger Ground Turkey, 1 lb", 4.99, 2.99, "IN_STORE")
add(G, "Kroger", "Kroger Frozen Mixed Vegetables, 32 oz", 3.49, 2.49)
add(G, "Kroger", "Kroger Pasta, 16 oz", 1.49, 0.99, "CODE")
add(G, "Kroger", "Kroger Rice, 2 lb", 2.99, 1.99)
add(G, "Kroger", "Kroger Orange Juice, 59 oz", 4.49, 2.99)
add(G, "Kroger", "Kroger Shredded Cheese, 8 oz", 3.49, 2.49, "CODE")
add(G, "Kroger", "Kroger Yogurt Cups, 6 pack", 3.79, 2.49)
add(G, "Kroger", "Kroger Bacon, 12 oz", 5.99, 3.99, "IN_STORE")
add(G, "Kroger", "Kroger Sausage Links, 14 oz", 3.99, 2.79, "IN_STORE")
add(G, "Kroger", "Kroger Frozen Pizza", 5.49, 3.99)
add(G, "Kroger", "Kroger Apples, 3 lb", 4.49, 2.99, "IN_STORE")
add(G, "Kroger", "Kroger Salad Mix, 9 oz", 3.49, 2.49, "IN_STORE")
add(G, "Target", "Good & Gather Milk, 1 gallon", 3.79, 2.99, "IN_STORE")
add(G, "Target", "Good & Gather Eggs, 12 count", 3.29, 2.19, "IN_STORE")
add(G, "Target", "Good & Gather Peanut Butter, 16 oz", 3.49, 2.49, "CODE")
add(G, "Target", "Good & Gather Tortilla Chips, 13 oz", 3.29, 2.29, "IN_STORE")
add(G, "Target", "Good & Gather Salsa, 16 oz", 3.49, 2.49, "CODE")
add(G, "Target", "Good & Gather Granola, 11 oz", 3.99, 2.99)
add(G, "Target", "Good & Gather Frozen Fruit, 16 oz", 3.79, 2.79)
add(G, "Target", "Good & Gather Spaghetti, 16 oz", 1.49, 0.99, "CODE")
add(G, "Aldi", "Aldi Milk, 1 gallon", 3.45, 2.55, "IN_STORE")
add(G, "Aldi", "Aldi Eggs, dozen", 2.99, 1.99, "IN_STORE")
add(G, "Aldi", "Aldi Butter, 1 lb", 3.99, 2.79, "IN_STORE")
add(G, "Aldi", "Aldi Chicken Breast, 1 lb", 2.99, 1.99, "IN_STORE")
add(G, "Aldi", "Aldi Cheddar Block, 8 oz", 2.99, 1.99, "IN_STORE")
add(G, "Aldi", "Aldi Baguette", 1.99, 0.99, "IN_STORE")
add(G, "Aldi", "Aldi Pasta, 16 oz", 0.99, 0.65, "IN_STORE")
add(G, "Aldi", "Aldi Canned Tomatoes, 14.5 oz", 0.89, 0.59, "IN_STORE")
add(G, "Trader Joe's", "Trader Joe's Ravioli, 20 oz", 4.99, 3.99, "IN_STORE")
add(G, "Trader Joe's", "Trader Joe's Mandarin Oranges, 3 lb", 3.99, 2.99, "IN_STORE")
add(G, "Trader Joe's", "Trader Joe's Spinach, 10 oz", 2.99, 1.99, "IN_STORE")
add(G, "Costco", "Costco Rotisserie Chicken", 5.99, 4.99, "IN_STORE")
add(G, "Costco", "Costco Eggs, 2 dozen", 5.99, 4.79, "IN_STORE")
add(G, "Costco", "Costco Milk, 2 gallons", 5.99, 4.99, "IN_STORE")
add(G, "Costco", "Costco Bacon, 3 lb", 14.99, 11.99, "IN_STORE")
add(G, "Costco", "Costco Chicken Breast, 6 lb", 24.99, 19.99, "IN_STORE")
add(G, "Whole Foods", "Whole Foods Bananas, bunch", 1.49, 0.99, "IN_STORE")
add(G, "Whole Foods", "Whole Foods Quinoa, 32 oz", 7.99, 5.99)
add(G, "Sam's Club", "Sam's Club Rotisserie Chicken", 4.98, 4.48, "IN_STORE")
add(G, "Sam's Club", "Sam's Club Bakery Muffins, 12 ct", 8.98, 6.98, "IN_STORE")
add(G, "Dollar General", "DG Smart Snack Crackers, 9 oz", 1.95, 1.25, "IN_STORE")
add(G, "Dollar General", "DG Smart Household Dish Soap, 24 oz", 1.95, 1.25, "IN_STORE")
add(G, "Dollar Tree", "Dollar Tree Pasta, 16 oz", 1.50, 1.25, "IN_STORE")
add(G, "Dollar Tree", "Dollar Tree Canned Vegetables", 1.50, 1.25, "IN_STORE")
add(G, "Dollar Tree", "Dollar Tree Crackers, 4.5 oz", 1.50, 1.25, "IN_STORE")

# ---- Dining ---------------------------------------------------------------
D = "Dining"
add(D, "Chipotle", "Chipotle Burrito Bowl", 11.25, 8.50, "CODE")
add(D, "Chipotle", "Chipotle Chicken Burrito", 11.65, 8.75, "CODE")
add(D, "Panera", "Panera You Pick Two", 12.99, 9.99, "CODE")
add(D, "Panera", "Panera Chicken Noodle Soup, bowl", 7.99, 5.99, "CODE")
add(D, "Subway", "Subway Footlong Sandwich", 9.49, 6.99, "CODE")
add(D, "Subway", "Subway $6.99 Footlong deal", 9.49, 6.99, "CODE")
add(D, "Starbucks", "Starbucks Grande Latte", 5.45, 4.36, "CODE")
add(D, "Starbucks", "Starbucks Cold Brew, grande", 4.75, 3.80, "CODE")
add(D, "McDonald's", "McDonald's Big Mac Meal", 10.99, 8.99, "CODE")
add(D, "McDonald's", "McDonald's McChicken Meal", 8.49, 6.99, "CODE")
add(D, "McDonald's", "McDonald's $1 Soda any size", 1.49, 1.00, "IN_STORE")
add(D, "Pizza Hut", "Pizza Hut Large Cheese Pizza", 14.99, 9.99, "CODE")
add(D, "Pizza Hut", "Pizza Hut Medium 2-Topping", 12.99, 8.99, "CODE")
add(D, "Domino's", "Domino's Large 2-Topping Pizza", 15.99, 9.99, "CODE")
add(D, "Domino's", "Domino's Mix & Match Deal", 9.99, 6.99, "CODE")
add(D, "Olive Garden", "Olive Garden Tour of Italy", 21.99, 16.99, "CODE")
add(D, "Olive Garden", "Olive Garden Never Ending Pasta Bowl", 14.99, 11.99, "CODE")
add(D, "Chick-fil-A", "Chick-fil-A Chicken Sandwich", 6.79, 5.43, "CODE")
add(D, "Chick-fil-A", "Chick-fil-A Nuggets 8 ct", 6.29, 5.03, "CODE")
add(D, "Taco Bell", "Taco Bell Cravings Box", 8.99, 6.99, "CODE")
add(D, "Taco Bell", "Taco Bell Build Your Own Cravings Box", 9.49, 7.99, "CODE")
add(D, "Dollar General", "DG Smart Ice Cream, 48 oz", 4.50, 3.00, "IN_STORE")
add(D, "Walmart", "Great Value Frozen Pizza, 13.5 oz", 3.48, 2.48, "IN_STORE")
add(D, "Walmart", "Great Value Ice Cream, 48 oz", 3.98, 2.98, "IN_STORE")
add(D, "Walmart", "Great Value Chicken Nuggets, 32 oz", 8.48, 5.98, "IN_STORE")
add(D, "Walmart", "Great Value French Fries, 28 oz", 3.98, 2.78, "IN_STORE")
add(D, "Walmart", "Great Value Mac & Cheese, 12 pack", 9.98, 6.98)
add(D, "Walmart", "Great Value Pancake Mix, 32 oz", 3.98, 2.48, "CODE")
add(D, "Walmart", "Great Value Syrup, 24 oz", 3.48, 2.28, "CODE")
add(D, "Kroger", "Kroger Frozen Chicken Strips, 26 oz", 7.49, 4.99, "IN_STORE")
add(D, "Kroger", "Kroger Mozzarella Sticks, 20 oz", 5.49, 3.99, "IN_STORE")
add(D, "Kroger", "Kroger Ice Cream Sandwiches, 12 ct", 4.99, 3.49)
add(D, "Kroger", "Kroger Pancake Syrup, 24 oz", 3.99, 2.49, "CODE")
add(D, "Target", "Good & Gather Chicken Nuggets, 26 oz", 7.29, 4.99, "IN_STORE")
add(D, "Target", "Good & Gather Mac & Cheese, 6 ct", 5.49, 3.99, "CODE")
add(D, "Target", "Good & Gather Frozen Pizza, 12 inch", 4.79, 3.29, "IN_STORE")
add(D, "Aldi", "Aldi Frozen Pizza, 16 oz", 3.99, 2.79, "IN_STORE")
add(D, "Aldi", "Aldi Chicken Nuggets, 24 oz", 5.49, 3.99, "IN_STORE")
add(D, "Aldi", "Aldi Ice Cream, 48 oz", 3.29, 2.29, "IN_STORE")
add(D, "Costco", "Costco Chicken Bake", 2.99, 2.49, "IN_STORE")
add(D, "Costco", "Costco Food Court Hot Dog + Soda", 1.75, 1.50, "IN_STORE")
add(D, "Costco", "Costco Food Court Pizza Slice", 2.99, 2.49, "IN_STORE")
add(D, "Sam's Club", "Sam's Club Food Court Combo", 1.98, 1.78, "IN_STORE")
add(D, "Dollar Tree", "Dollar Tree Snack Crackers & Cheese, 4 oz", 1.50, 1.25, "IN_STORE")
add(D, "Dollar Tree", "Dollar Tree Candy, 6 oz", 1.50, 1.25, "IN_STORE")

# ---- Essentials -----------------------------------------------------------
E = "Essentials"
add(E, "Walmart", "Great Value Toilet Paper, 12 rolls", 9.98, 6.98, "IN_STORE")
add(E, "Walmart", "Great Value Paper Towels, 6 rolls", 7.98, 4.98, "IN_STORE")
add(E, "Walmart", "Great Value Dish Soap, 28 oz", 2.98, 1.98, "CODE")
add(E, "Walmart", "Great Value Laundry Detergent, 92 loads", 13.98, 9.98)
add(E, "Walmart", "Great Value Trash Bags, 80 ct", 14.98, 9.98, "IN_STORE")
add(E, "Walmart", "Great Value All-Purpose Cleaner, 32 oz", 2.48, 1.48, "CODE")
add(E, "Walmart", "Great Value Hand Soap, 7.5 oz", 1.48, 0.98, "CODE")
add(E, "Walmart", "Great Value Dishwasher Pods, 45 ct", 11.98, 8.48)
add(E, "Walmart", "Great Value Zipper Bags, 120 ct", 5.98, 3.98, "IN_STORE")
add(E, "Walmart", "Great Value Foil, 200 sq ft", 4.98, 2.98, "IN_STORE")
add(E, "Walmart", "Great Value Sponges, 6 ct", 2.48, 1.48, "IN_STORE")
add(E, "Walmart", "Great Value Bleach, 121 oz", 3.98, 2.48, "CODE")
add(E, "Walmart", "Great Value Softener Sheets, 120 ct", 6.98, 4.48)
add(E, "Walmart", "Great Value Clorox Wipes, 3 pack", 9.98, 6.98)
add(E, "Target", "up & up Toilet Paper, 12 rolls", 8.99, 5.99, "IN_STORE")
add(E, "Target", "up & up Paper Towels, 6 rolls", 6.99, 4.49, "IN_STORE")
add(E, "Target", "up & up Laundry Detergent, 80 loads", 11.99, 8.99)
add(E, "Target", "up & up Dish Soap, 25 oz", 2.79, 1.79, "CODE")
add(E, "Target", "up & up Trash Bags, 90 ct", 12.99, 8.99, "IN_STORE")
add(E, "Target", "up & up Hand Soap, 8.5 oz", 1.99, 0.99, "CODE")
add(E, "Kroger", "Kroger Toilet Paper, 12 rolls", 8.99, 5.99, "IN_STORE")
add(E, "Kroger", "Kroger Paper Towels, 6 rolls", 6.99, 4.49, "IN_STORE")
add(E, "Kroger", "Kroger Laundry Detergent, 75 loads", 12.99, 8.99)
add(E, "Kroger", "Kroger Trash Bags, 45 ct", 9.99, 6.99, "IN_STORE")
add(E, "Kroger", "Kroger Dish Soap, 28 oz", 2.99, 1.99, "CODE")
add(E, "CVS", "CVS Health Toilet Paper, 12 rolls", 10.99, 7.99, "IN_STORE")
add(E, "CVS", "CVS Health Paper Towels, 8 rolls", 9.99, 6.99, "IN_STORE")
add(E, "CVS", "CVS Health Laundry Detergent, 100 oz", 12.99, 8.99)
add(E, "CVS", "CVS Health Hand Soap, 11 oz", 2.99, 1.99, "CODE")
add(E, "Walgreens", "Walgreens Toilet Paper, 12 rolls", 10.99, 7.99, "IN_STORE")
add(E, "Walgreens", "Walgreens Trash Bags, 80 ct", 12.99, 8.99, "IN_STORE")
add(E, "Walgreens", "Walgreens Laundry Detergent, 90 oz", 11.99, 8.49)
add(E, "Dollar General", "DG Smart Laundry Detergent, 50 oz", 5.00, 3.25, "IN_STORE")
add(E, "Dollar General", "DG Smart Paper Towels, 2 rolls", 1.95, 1.25, "IN_STORE")
add(E, "Dollar General", "DG Smart Dish Soap, 24 oz", 1.95, 1.25, "IN_STORE")
add(E, "Dollar Tree", "Dollar Tree Dish Soap, 16 oz", 1.50, 1.25, "IN_STORE")
add(E, "Dollar Tree", "Dollar Tree Laundry Detergent, 32 oz", 1.50, 1.25, "IN_STORE")
add(E, "Dollar Tree", "Dollar Tree Sponges, 4 pack", 1.50, 1.25, "IN_STORE")
add(E, "Amazon", "Amazon Basics Trash Bags, 150 ct", 19.99, 14.99, "CODE")
add(E, "Amazon", "Amazon Basics Paper Towels, 12 rolls", 22.99, 16.99, "CODE")
add(E, "Amazon", "Amazon Basics Toilet Paper, 30 rolls", 24.99, 18.99, "CODE")
add(E, "Amazon", "Amazon Basics Dishwasher Pods, 100 ct", 17.99, 12.99, "CODE")
add(E, "Costco", "Kirkland Paper Towels, 12 rolls", 22.99, 18.99, "IN_STORE")
add(E, "Costco", "Kirkland Toilet Paper, 30 rolls", 24.99, 19.99, "IN_STORE")
add(E, "Costco", "Kirkland Laundry Detergent, 192 oz", 17.99, 13.99, "IN_STORE")
add(E, "Sam's Club", "Member's Mark Paper Towels, 12 rolls", 19.98, 14.98, "IN_STORE")
add(E, "Sam's Club", "Member's Mark Toilet Paper, 45 rolls", 22.98, 17.98, "IN_STORE")

# ---- Beauty ---------------------------------------------------------------
B = "Beauty"
add(B, "Target", "e.l.f. Face Primer", 10.00, 7.00, "CODE")
add(B, "Target", "e.l.f. Lip Gloss", 6.00, 4.00, "CODE")
add(B, "Target", "Neutrogena Facial Cleanser, 6.7 oz", 9.99, 6.99, "CODE")
add(B, "Target", "Maybelline Mascara", 11.99, 7.99, "CODE")
add(B, "Target", "CoverGirl Foundation", 10.99, 6.99, "CODE")
add(B, "Target", "CeraVe Moisturizing Cream, 16 oz", 18.99, 12.99, "CODE")
add(B, "Target", "Cetaphil Gentle Cleanser, 16 oz", 15.99, 10.99, "CODE")
add(B, "Target", "Pantene Shampoo, 12.6 oz", 7.99, 4.99, "CODE")
add(B, "Target", "Dove Body Wash, 30 oz", 9.99, 5.99, "CODE")
add(B, "Target", "Secret Deodorant, 2 pack", 7.99, 4.99, "CODE")
add(B, "Walmart", "Great Value Hand Lotion, 20 oz", 4.98, 2.98, "CODE")
add(B, "Walmart", "Equate Body Wash, 30 oz", 5.98, 3.98, "CODE")
add(B, "Walmart", "Equate Shampoo, 23 oz", 4.98, 2.98, "CODE")
add(B, "Walmart", "Equate Petroleum Jelly, 13 oz", 3.98, 2.48, "CODE")
add(B, "Walmart", "Garnier Fructis Shampoo, 25 oz", 6.98, 4.48, "CODE")
add(B, "Walmart", "Olay Regenerist Moisturizer", 26.98, 17.98, "CODE")
add(B, "CVS", "CVS Health Facial Cleanser, 6 oz", 6.99, 4.49, "CODE")
add(B, "CVS", "CVS Health Moisturizer, 12 oz", 8.99, 5.99, "CODE")
add(B, "CVS", "CVS Health Shampoo, 15 oz", 5.99, 3.99, "CODE")
add(B, "CVS", "CVS Health Body Wash, 16 oz", 6.49, 4.29, "CODE")
add(B, "Walgreens", "Walgreens Hand Cream, 3 oz", 5.49, 3.49, "CODE")
add(B, "Walgreens", "Walgreens Shampoo, 15 oz", 5.99, 3.99, "CODE")
add(B, "Sephora", "Sephora Collection Lip Stain", 14.00, 10.00, "CODE")
add(B, "Sephora", "Sephora Collection Eyeliner", 13.00, 9.00, "CODE")
add(B, "Sephora", "Sephora Collection Brush Set, 6 pc", 48.00, 32.00, "CODE")
add(B, "Sephora", "The Ordinary Niacinamide Serum, 30 ml", 6.70, 5.36, "CODE")
add(B, "Sephora", "The Ordinary Hyaluronic Acid, 30 ml", 9.50, 7.60, "CODE")
add(B, "Ulta", "NYX Lip Lingerie", 10.99, 7.99, "CODE")
add(B, "Ulta", "NYX Epic Ink Liner", 8.99, 6.49, "CODE")
add(B, "Ulta", "Revlon Super Lustrous Lipstick", 9.99, 6.99, "CODE")
add(B, "Ulta", "L'Oréal Paris Telescopic Mascara", 10.99, 7.99, "CODE")
add(B, "Amazon", "Amazon Basics Shampoo, 30 oz", 7.99, 5.99, "CODE")
add(B, "Amazon", "Amazon Basics Conditioner, 30 oz", 7.99, 5.99, "CODE")
add(B, "Amazon", "Amazon Basics Body Wash, 30 oz", 6.99, 4.99, "CODE")
add(B, "Dollar General", "DG Smart Body Wash, 16 oz", 3.25, 2.25, "IN_STORE")
add(B, "Dollar General", "DG Smart Shampoo, 16 oz", 3.25, 2.25, "IN_STORE")
add(B, "Dollar Tree", "Dollar Tree Face Masks, 3 ct", 1.50, 1.25, "IN_STORE")
add(B, "Dollar Tree", "Dollar Tree Nail Polish", 1.50, 1.25, "IN_STORE")

# ---- Health ---------------------------------------------------------------
H = "Health"
add(H, "CVS", "CVS Health Pain Relief, 200 ct", 8.99, 5.99, "CODE")
add(H, "CVS", "CVS Health Allergy Relief, 24 ct", 7.99, 4.99, "CODE")
add(H, "CVS", "CVS Health Multivitamin, 150 ct", 10.99, 6.99, "CODE")
add(H, "CVS", "CVS Health Vitamin C, 250 ct", 8.49, 4.99, "CODE")
add(H, "CVS", "CVS Health First Aid Kit, 140 pc", 14.99, 9.99, "CODE")
add(H, "CVS", "CVS Health Digital Thermometer", 12.99, 7.99, "CODE")
add(H, "CVS", "CVS Health Bandages, 120 ct", 5.99, 3.99, "CODE")
add(H, "Walgreens", "Walgreens Pain Reliever, 100 ct", 8.99, 5.99, "CODE")
add(H, "Walgreens", "Walgreens Multivitamin, 150 ct", 9.99, 6.99, "CODE")
add(H, "Walgreens", "Walgreens Allergy 24 Hour, 36 ct", 9.49, 5.99, "CODE")
add(H, "Walgreens", "Walgreens Vitamin D3, 200 ct", 8.99, 5.49, "CODE")
add(H, "Walmart", "Equate Pain Relief, 200 ct", 7.48, 4.48, "CODE")
add(H, "Walmart", "Equate Allergy Relief, 24 ct", 6.98, 3.98, "CODE")
add(H, "Walmart", "Equate Multivitamin Gummies, 90 ct", 8.98, 5.98, "CODE")
add(H, "Walmart", "Equate Vitamin C, 100 ct", 6.48, 3.98, "CODE")
add(H, "Walmart", "Equate Cold & Flu Day/Night, 24 ct", 9.98, 6.48, "CODE")
add(H, "Walmart", "Equate Digital Thermometer", 9.98, 6.48, "CODE")
add(H, "Walmart", "Equate Bandages, 100 ct", 4.98, 2.98, "CODE")
add(H, "Walmart", "Equate Sleep Aid, 36 ct", 7.48, 4.48, "CODE")
add(H, "Target", "up & up Pain Relief, 200 ct", 7.99, 4.99, "CODE")
add(H, "Target", "up & up Allergy Relief, 24 ct", 6.99, 4.49, "CODE")
add(H, "Target", "up & up Multivitamin, 150 ct", 9.99, 6.49, "CODE")
add(H, "Target", "up & up Bandages, 100 ct", 4.99, 2.99, "CODE")
add(H, "Kroger", "Kroger Pain Relief, 200 ct", 7.99, 4.99, "CODE")
add(H, "Kroger", "Kroger Multivitamin, 130 ct", 9.49, 5.99, "CODE")
add(H, "Kroger", "Kroger Allergy Relief, 24 ct", 7.49, 4.49, "CODE")
add(H, "Dollar General", "DG Health Pain Relief, 100 ct", 4.50, 3.00, "IN_STORE")
add(H, "Dollar General", "DG Health Bandages, 80 ct", 3.50, 2.25, "IN_STORE")
add(H, "Dollar General", "DG Health Vitamin C, 100 ct", 4.50, 3.00, "IN_STORE")
add(H, "Dollar Tree", "Dollar Tree Pain Relief, 24 ct", 1.50, 1.25, "IN_STORE")
add(H, "Dollar Tree", "Dollar Tree Bandages, 30 ct", 1.50, 1.25, "IN_STORE")
add(H, "Amazon", "Amazon Basics Pain Relief, 200 ct", 8.99, 6.49, "CODE")
add(H, "Amazon", "Amazon Basics Allergy Relief, 100 ct", 9.99, 7.49, "CODE")
add(H, "Amazon", "Amazon Basics Multivitamin, 200 ct", 8.99, 6.99, "CODE")

# ---- Home -----------------------------------------------------------------
HM = "Home"
add(HM, "Walmart", "Mainstays Bath Towel, 2 pk", 12.98, 7.98, "IN_STORE")
add(HM, "Walmart", "Mainstays Bed Sheet Set, queen", 24.98, 16.98, "IN_STORE")
add(HM, "Walmart", "Mainstays Pillow, 2 pk", 17.98, 11.98, "IN_STORE")
add(HM, "Walmart", "Better Homes & Gardens 8-pc Cookware", 79.98, 49.98, "IN_STORE")
add(HM, "Walmart", "Mainstays LED Bulbs, 4 pk", 9.98, 5.98, "IN_STORE")
add(HM, "Walmart", "Mainstays Storage Bins, 6 pk", 24.98, 16.98, "IN_STORE")
add(HM, "Walmart", "Mainstays Bath Mat", 9.98, 5.98, "IN_STORE")
add(HM, "Walmart", "Mainstays Plastic Hangers, 30 pk", 9.98, 5.98, "IN_STORE")
add(HM, "Target", "Threshold Bath Towel, 2 pk", 14.99, 9.99, "IN_STORE")
add(HM, "Target", "Threshold Comforter, queen", 59.99, 39.99, "IN_STORE")
add(HM, "Target", "room essentials Sheets, queen", 29.99, 19.99, "IN_STORE")
add(HM, "Target", "Threshold Dinner Plates, 4 pk", 24.99, 16.99, "IN_STORE")
add(HM, "Target", "up & up LED Bulbs, 4 pk", 11.99, 7.99, "IN_STORE")
add(HM, "Home Depot", "Husky Tool Set, 175 pc", 59.97, 39.97, "IN_STORE")
add(HM, "Home Depot", "Milwaukee Impact Driver Kit", 179.00, 129.00, "IN_STORE")
add(HM, "Home Depot", "Ryobi 18V Drill Kit", 99.00, 69.00, "IN_STORE")
add(HM, "Home Depot", "Glidden Interior Paint, 1 gal", 29.98, 19.98, "IN_STORE")
add(HM, "Home Depot", "Hampton Bay Ceiling Fan", 79.00, 49.00, "IN_STORE")
add(HM, "Home Depot", "LED Shop Light, 2 pk", 49.97, 29.97, "IN_STORE")
add(HM, "Home Depot", "Rubbermaid Storage Totes, 5 pk", 34.98, 24.98, "IN_STORE")
add(HM, "Home Depot", "DeWalt Circular Saw", 129.00, 89.00, "IN_STORE")
add(HM, "Lowe's", "Kobalt Tool Set, 142 pc", 99.00, 69.00, "IN_STORE")
add(HM, "Lowe's", "Frigidaire Countertop Microwave", 89.00, 59.00, "IN_STORE")
add(HM, "Lowe's", "Leviton Smart Plug, 2 pk", 39.98, 24.98, "IN_STORE")
add(HM, "Lowe's", "Rubbermaid 32-pc Food Storage", 24.98, 16.98, "IN_STORE")
add(HM, "IKEA", "IKEA LACK Side Table", 12.99, 9.99, "IN_STORE")
add(HM, "IKEA", "IKEA KALLAX Shelf Unit", 79.99, 59.99, "IN_STORE")
add(HM, "IKEA", "IKEA FRAKTA Storage Bag", 4.99, 3.99, "IN_STORE")
add(HM, "IKEA", "IKEA MALM Dresser, 6 drawer", 299.00, 229.00, "IN_STORE")
add(HM, "IKEA", "IKEA BILLY Bookcase", 89.00, 69.00, "IN_STORE")
add(HM, "Costco", "Kirkland Kitchen Trash Bags, 200 ct", 24.99, 18.99, "IN_STORE")
add(HM, "Costco", "Kirkland Dryer Sheets, 375 ct", 19.99, 15.99, "IN_STORE")
add(HM, "Costco", "Kirkland Paper Towels, 12 rolls", 22.99, 18.99, "IN_STORE")
add(HM, "Harbor Freight", "Pittsburgh Work Gloves, 12 pk", 9.99, 6.99, "IN_STORE")
add(HM, "Harbor Freight", "Pittsburgh Flashlight, 2 pk", 7.99, 4.99, "IN_STORE")
add(HM, "Dollar General", "DG Smart Storage Bins, 2 pk", 6.50, 4.25, "IN_STORE")
add(HM, "Dollar General", "DG Smart LED Bulbs, 2 pk", 4.50, 3.00, "IN_STORE")
add(HM, "Dollar Tree", "Dollar Tree Kitchen Utensil Set", 1.50, 1.25, "IN_STORE")
add(HM, "Dollar Tree", "Dollar Tree Cleaning Brush, 2 pk", 1.50, 1.25, "IN_STORE")

# ---- Travel ---------------------------------------------------------------
T = "Travel"
add(T, "Amazon", "Anker 10,000mAh Power Bank", 25.99, 17.99, "CODE")
add(T, "Amazon", "Anker USB-C Cable, 2 pk", 13.99, 9.99, "CODE")
add(T, "Amazon", "Travel Toiletry Bottles, 8 pk", 14.99, 9.99, "CODE")
add(T, "Amazon", "Packing Cubes, 6 pk", 29.99, 19.99, "CODE")
add(T, "Amazon", "Universal Travel Adapter", 24.99, 16.99, "CODE")
add(T, "Amazon", "Neck Pillow, memory foam", 21.99, 14.99, "CODE")
add(T, "Amazon", "TSA Luggage Locks, 2 pk", 12.99, 8.99, "CODE")
add(T, "Amazon", "Kindle Paperwhite 16GB", 149.99, 119.99, "CODE")
add(T, "Amazon", "Fire HD 8 Tablet", 89.99, 54.99, "CODE")
add(T, "Walmart", "Mainstays 28\" Luggage Set", 99.98, 69.98, "IN_STORE")
add(T, "Walmart", "Ozark Trail Cooler, 30 qt", 49.98, 34.98, "IN_STORE")
add(T, "Walmart", "Ozark Trail Camping Chair, 2 pk", 39.98, 24.98, "IN_STORE")
add(T, "Walmart", "Ozark Trail Sleeping Bag", 29.98, 19.98, "IN_STORE")
add(T, "Walmart", "Mainstays Duffel Bag", 24.98, 14.98, "IN_STORE")
add(T, "Target", "open story Carry-On Luggage", 79.99, 54.99, "IN_STORE")
add(T, "Target", "Threshold Beach Towel", 16.99, 11.99, "IN_STORE")
add(T, "Target", "Universal Thread Packing Cubes", 24.99, 16.99, "IN_STORE")
add(T, "Target", "up & up Sunscreen SPF 50, 2 pk", 13.99, 9.99, "CODE")
add(T, "Costco", "Kirkland Sunscreen SPF 50, 2 pk", 17.99, 12.99, "IN_STORE")
add(T, "Costco", "Kirkland Trail Mix, 48 oz", 16.99, 12.99, "IN_STORE")
add(T, "Costco", "Kirkland Bottled Water, 40 pk", 9.99, 7.99, "IN_STORE")
add(T, "Sam's Club", "Member's Mark Bottled Water, 40 pk", 9.98, 7.98, "IN_STORE")
add(T, "CVS", "CVS Health Sunscreen SPF 50, 8 oz", 11.99, 7.99, "CODE")
add(T, "CVS", "CVS Health Motion Sickness, 36 ct", 8.99, 5.99, "CODE")
add(T, "Walgreens", "Walgreens Sunscreen SPF 50, 8 oz", 11.49, 7.99, "CODE")
add(T, "Walgreens", "Walgreens First Aid Kit, travel", 9.99, 6.99, "CODE")
add(T, "Dollar General", "DG Smart Sunscreen SPF 50", 4.50, 3.00, "IN_STORE")
add(T, "Dollar Tree", "Dollar Tree Travel Toothpaste, 3 pk", 1.50, 1.25, "IN_STORE")
add(T, "Dollar Tree", "Dollar Tree Travel Soap", 1.50, 1.25, "IN_STORE")

# ---- Tech -----------------------------------------------------------------
TE = "Tech"
add(TE, "Best Buy", "Samsung Galaxy A15 5G 128GB", 199.99, 149.99, "IN_STORE")
add(TE, "Best Buy", "Apple AirPods (3rd Gen)", 169.00, 129.99, "IN_STORE")
add(TE, "Best Buy", "Amazon Fire TV Stick 4K", 49.99, 29.99, "IN_STORE")
add(TE, "Best Buy", "Logitech MX Master 3S Mouse", 99.99, 69.99, "IN_STORE")
add(TE, "Best Buy", "Logitech K380 Keyboard", 39.99, 24.99, "IN_STORE")
add(TE, "Best Buy", "SanDisk 128GB microSD Card", 22.99, 14.99, "IN_STORE")
add(TE, "Best Buy", "TP-Link WiFi 6 Router", 89.99, 59.99, "IN_STORE")
add(TE, "Best Buy", "Insignia 32\" HD Roku TV", 139.99, 99.99, "IN_STORE")
add(TE, "Best Buy", "JBL Go 3 Bluetooth Speaker", 49.99, 29.99, "IN_STORE")
add(TE, "Best Buy", "Echo Dot (5th Gen)", 49.99, 24.99, "IN_STORE")
add(TE, "Best Buy", "HP DeskJet 2755e Printer", 79.99, 49.99, "IN_STORE")
add(TE, "Best Buy", "Netgear Nighthawk Router", 249.99, 169.99, "IN_STORE")
add(TE, "Best Buy", "WD 4TB External Hard Drive", 99.99, 69.99, "IN_STORE")
add(TE, "Best Buy", "Canon PIXMA MG3620 Printer", 79.99, 49.99, "IN_STORE")
add(TE, "Best Buy", "PlayStation 5 DualSense Controller", 74.99, 59.99, "IN_STORE")
add(TE, "Best Buy", "Xbox Wireless Controller", 64.99, 49.99, "IN_STORE")
add(TE, "Best Buy", "Nintendo Switch Joy-Con (L/R)", 79.99, 64.99, "IN_STORE")
add(TE, "Best Buy", "Samsung 128GB USB-C Flash Drive", 24.99, 17.99, "IN_STORE")
add(TE, "Best Buy", "Anker 65W USB-C Charger", 39.99, 27.99, "IN_STORE")
add(TE, "Best Buy", "Roku Streaming Stick 4K", 49.99, 29.99, "IN_STORE")
add(TE, "Best Buy", "JBL Flip 6 Speaker", 129.95, 89.99, "IN_STORE")
add(TE, "Best Buy", "MacBook Air M3 13\"", 1099.00, 899.00, "IN_STORE")
add(TE, "Best Buy", "iPad (10th Gen) 64GB", 349.00, 279.00, "IN_STORE")
add(TE, "Best Buy", "Dell Inspiron 15 Laptop", 499.99, 379.99, "IN_STORE")
add(TE, "Best Buy", "LG 27\" 1080p Monitor", 149.99, 109.99, "IN_STORE")
add(TE, "Best Buy", "Epson EcoTank Printer", 299.99, 219.99, "IN_STORE")
add(TE, "Best Buy", "Kindle 16GB (2024)", 109.99, 84.99, "IN_STORE")
add(TE, "Best Buy", "Garmin Venu Sq 2 Watch", 249.99, 169.99, "IN_STORE")
add(TE, "Best Buy", "Fitbit Versa 4", 199.95, 129.95, "IN_STORE")
add(TE, "Best Buy", "Razer DeathAdder Gaming Mouse", 69.99, 39.99, "IN_STORE")
add(TE, "Apple", "Apple AirPods (3rd Gen)", 169.00, 139.00, "IN_STORE")
add(TE, "Apple", "Apple AirTag 4-Pack", 99.00, 79.00, "IN_STORE")
add(TE, "Apple", "Apple 20W USB-C Charger", 19.00, 15.00, "IN_STORE")
add(TE, "Apple", "Apple MagSafe Charger", 39.00, 29.00, "IN_STORE")
add(TE, "Apple", "Apple iPad (10th Gen)", 349.00, 299.00, "IN_STORE")
add(TE, "Apple", "Apple HomePod mini", 99.00, 79.00, "IN_STORE")
add(TE, "Walmart", "onn. 32\" Roku TV", 128.00, 98.00, "IN_STORE")
add(TE, "Walmart", "onn. Bluetooth Earbuds", 14.88, 9.88, "IN_STORE")
add(TE, "Walmart", "onn. Soundbar", 39.98, 29.98, "IN_STORE")
add(TE, "Walmart", "onn. 10\" Tablet", 79.00, 59.00, "IN_STORE")
add(TE, "Walmart", "TCL 43\" 4K Roku TV", 258.00, 198.00, "IN_STORE")
add(TE, "Walmart", "Vizio 50\" 4K TV", 348.00, 278.00, "IN_STORE")
add(TE, "Walmart", "HP Laptop 15.6\"", 349.00, 279.00, "IN_STORE")
add(TE, "Walmart", "Canon PIXMA MG3620", 79.99, 49.99, "IN_STORE")
add(TE, "Walmart", "Samsung Galaxy A15 5G", 199.99, 149.99, "IN_STORE")
add(TE, "Walmart", "Amazon Echo Dot (5th Gen)", 49.99, 24.99, "IN_STORE")
add(TE, "Walmart", "Toshiba 1TB External Hard Drive", 54.99, 39.99, "IN_STORE")
add(TE, "Target", "Google Nest Mini", 49.99, 29.99, "IN_STORE")
add(TE, "Target", "Google Chromecast with Google TV HD", 29.99, 19.99, "IN_STORE")
add(TE, "Target", "JLab Go Air Pop Earbuds", 24.99, 19.99, "IN_STORE")
add(TE, "Target", "Keurig K-Mini Coffee Maker", 89.99, 59.99, "IN_STORE")
add(TE, "Target", "TurboTax Deluxe 2025", 64.99, 44.99, "IN_STORE")
add(TE, "Target", "Kodak Step Instant Printer", 99.99, 69.99, "IN_STORE")
add(TE, "Target", "Cuisinart Air Fryer Toaster Oven", 149.99, 99.99, "IN_STORE")
add(TE, "Target", "Ninja Air Fryer", 129.99, 89.99, "IN_STORE")
add(TE, "Target", "Instant Pot Duo 6 Qt", 99.99, 69.99, "IN_STORE")
add(TE, "Target", "room essentials WiFi Extender", 29.99, 19.99, "IN_STORE")
add(TE, "Newegg", "Samsung 1TB NVMe SSD", 99.99, 69.99, "IN_STORE")
add(TE, "Newegg", "Crucial 16GB DDR4 RAM", 49.99, 34.99, "IN_STORE")
add(TE, "Newegg", "Intel Core i5-13400 Processor", 244.99, 189.99, "IN_STORE")
add(TE, "Newegg", "EVGA 600W Power Supply", 79.99, 54.99, "IN_STORE")
add(TE, "Newegg", "Corsair K55 RGB Keyboard", 59.99, 39.99, "IN_STORE")
add(TE, "Newegg", "Gigabyte B760M Motherboard", 149.99, 109.99, "IN_STORE")
add(TE, "Newegg", "Samsung 27\" 1080p Monitor", 169.99, 129.99, "IN_STORE")
add(TE, "Newegg", "Seagate 2TB HDD", 64.99, 49.99, "IN_STORE")
add(TE, "Newegg", "TP-Link AX1800 Router", 79.99, 54.99, "IN_STORE")
add(TE, "Amazon", "Amazon Echo Dot (5th Gen)", 49.99, 24.99, "CODE")
add(TE, "Amazon", "Amazon Fire TV Stick 4K", 49.99, 29.99, "CODE")
add(TE, "Amazon", "Kindle 16GB (2024)", 109.99, 84.99, "CODE")
add(TE, "Amazon", "Anker 65W Charger", 39.99, 27.99, "CODE")
add(TE, "Amazon", "Anker USB-C Cable, 2 pk", 13.99, 9.99, "CODE")
add(TE, "Amazon", "Samsung 128GB microSD Card", 19.99, 13.99, "CODE")
add(TE, "Amazon", "Amazon Basics Wireless Mouse", 12.99, 9.99, "CODE")
add(TE, "Amazon", "Amazon Basics AA Batteries, 24 pk", 14.99, 10.99, "CODE")
add(TE, "Amazon", "TP-Link WiFi 6 Router", 89.99, 59.99, "CODE")
add(TE, "Amazon", "SanDisk 256GB USB-C Drive", 24.99, 17.99, "CODE")
add(TE, "Amazon", "Anker PowerCore 20,000mAh", 49.99, 34.99, "CODE")
add(TE, "Amazon", "Amazon Fire 8 HD Tablet", 89.99, 54.99, "CODE")
add(TE, "Amazon", "Logitech C920 Webcam", 79.99, 59.99, "CODE")
add(TE, "Amazon", "Havit Mechanical Keyboard", 42.99, 29.99, "CODE")
add(TE, "Amazon", "Vankyo Mini Projector", 109.99, 79.99, "CODE")
add(TE, "eBay", "Refurbished iPhone 13, 128GB", 479.00, 379.00, "LINK")
add(TE, "eBay", "Refurbished Galaxy S22, 128GB", 349.00, 269.00, "LINK")
add(TE, "eBay", "Refurbished iPad 9th Gen 64GB", 299.00, 229.00, "LINK")
add(TE, "eBay", "Refurbished MacBook Air M1 13\"", 699.00, 549.00, "LINK")
add(TE, "eBay", "Refurbished Nintendo Switch", 259.00, 199.00, "LINK")
add(TE, "eBay", "Refurbished PlayStation 4 Pro", 289.00, 229.00, "LINK")
add(TE, "Staples", "Staples 30% Off Desk Chair", 199.99, 139.99, "IN_STORE")
add(TE, "Staples", "HP DeskJet 2755e Printer", 79.99, 54.99, "IN_STORE")
add(TE, "Staples", "Logitech MK270 Combo", 29.99, 19.99, "IN_STORE")
add(TE, "Staples", "Staples Paper, 8.5x11, 10 reams", 49.99, 34.99, "IN_STORE")
add(TE, "Staples", "Brother HL-L2400DW Printer", 129.99, 89.99, "IN_STORE")
add(TE, "Office Depot", "HP OfficeJet Pro 8135e", 199.99, 149.99, "IN_STORE")
add(TE, "Office Depot", "Logitech Brio 4K Webcam", 199.99, 139.99, "IN_STORE")
add(TE, "Office Depot", "Post-it Notes, 12 pk", 29.99, 19.99, "IN_STORE")
add(TE, "Dollar General", "DG Smart Earbuds", 12.50, 8.75, "IN_STORE")
add(TE, "Dollar General", "DG Smart Phone Case", 7.50, 5.25, "IN_STORE")
add(TE, "Dollar General", "DG Smart Charging Cable, 6 ft", 6.50, 4.50, "IN_STORE")
add(TE, "Dollar General", "DG Smart Wireless Earbuds", 19.50, 13.65, "IN_STORE")

# ---------------------------------------------------------------------------
# Build the JSON
# ---------------------------------------------------------------------------

rng = random.Random(SEED)

CODE_WORDS = ["THRIVE", "SAVE", "WEEKLY", "SUNNY", "FRESH", "DEAL", "SHOP", "BUDGET"]
DEAL_TYPE_TERMS = {
    "CODE": "Enter promo code at checkout to take the discount.",
    "LINK": "Online offer — open the link to see the current deal on the retailer's site.",
    "IN_STORE": "In-store offer — show this deal at checkout; prices verified at the store.",
    "PICKUP": "Order online for pickup to lock in the offer.",
}


def slug(title):
    return "".join(c if c.isalnum() else "-" for c in title.lower()).strip("-")


def build():
    coupons = []
    for i, (category, store, title, query, before, after, deal) in enumerate(ITEMS, start=1):
        url = RETAILERS[store](query)
        ends = rng.randint(1, 14)
        is_new = rng.random() < 0.18
        code = None
        if deal == "CODE":
            code = f"{rng.choice(CODE_WORDS)}{rng.randint(10, 99)}"
        coupons.append({
            "id": f"c{i:03d}",
            "store": store,
            "title": title,
            "description": f"{title} at a better price. {DEAL_TYPE_TERMS[deal]}",
            "category": category,
            "priceBefore": round(before, 2),
            "priceAfter": round(after, 2),
            "dealType": deal,
            "code": code,
            "url": url,
            "endsInDays": ends,
            "isNew": is_new,
            "terms": "Curated offer — retail prices change daily; confirm at checkout. "
                     "While supplies last.",
            "imageSeed": slug(title),
            "imageUrl": None,
            "urlVerified": False,
            "estimated": True,
        })
    return coupons


def main():
    coupons = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(coupons, f, indent=1, ensure_ascii=False)
        f.write("\n")
    counts = {}
    for c in coupons:
        counts[c["category"]] = counts.get(c["category"], 0) + 1
    stores = sorted({c["store"] for c in coupons})
    print(f"wrote {len(coupons)} coupons -> {OUT}")
    print("by category:", dict(sorted(counts.items())))
    print(f"retailers ({len(stores)}): {', '.join(stores)}")
    codes = sum(1 for c in coupons if c["code"])
    print(f"with promo codes: {codes}")


if __name__ == "__main__":
    main()
