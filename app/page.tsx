"use client";

import { useEffect, useMemo, useState } from "react";
import couponsData from "./data/coupons.json";
import recipesData from "./data/recipes.json";
import dealsData from "./data/deals.json";

type Tab = "savings" | "recipes" | "pantry" | "budget";
type PantryItem = { id: string; name: string; location: string; quantity: number; expires?: string };
type ShopItem = { id: string; name: string; price: number; checked: boolean };
type Recipe = (typeof recipesData)[number];

const tabs: { id: Tab; label: string; icon: string }[] = [
  { id: "savings", label: "Savings", icon: "🏷️" },
  { id: "recipes", label: "Recipes", icon: "🍲" },
  { id: "pantry", label: "Pantry", icon: "🧺" },
  { id: "budget", label: "Budget", icon: "💳" },
];

function money(value: number) { return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(value); }

export default function Home() {
  const [tab, setTab] = useState<Tab>("savings");
  const [query, setQuery] = useState("");
  const [favorites, setFavorites] = useState<string[]>([]);
  const [pantry, setPantry] = useState<PantryItem[]>([]);
  const [shopping, setShopping] = useState<ShopItem[]>([]);
  const [budget, setBudget] = useState(75);
  const [people, setPeople] = useState(4);
  const [selectedRecipe, setSelectedRecipe] = useState<Recipe | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    try {
      setFavorites(JSON.parse(localStorage.getItem("thrive-favorites") || "[]"));
      setPantry(JSON.parse(localStorage.getItem("thrive-pantry") || "[]"));
      setShopping(JSON.parse(localStorage.getItem("thrive-shopping") || "[]"));
    } finally { setReady(true); }
  }, []);
  useEffect(() => { if (ready) localStorage.setItem("thrive-favorites", JSON.stringify(favorites)); }, [favorites, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-pantry", JSON.stringify(pantry)); }, [pantry, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-shopping", JSON.stringify(shopping)); }, [shopping, ready]);

  const filteredCoupons = couponsData.filter(c => `${c.title} ${c.store} ${c.category}`.toLowerCase().includes(query.toLowerCase()));
  const filteredRecipes = recipesData.filter(r => `${r.name} ${r.description} ${r.tags.join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  const total = shopping.reduce((sum, item) => sum + item.price, 0);
  const pantryNames = pantry.map(p => p.name.toLowerCase());
  const mealMatches = useMemo(() => recipesData.map(recipe => ({ recipe, matches: recipe.ingredients.filter(i => pantryNames.some(p => i.name.toLowerCase().includes(p) || p.includes(i.name.toLowerCase()))).length })).sort((a, b) => b.matches - a.matches).slice(0, 3), [pantryNames.join("|")]);

  function toggleFavorite(id: string) { setFavorites(v => v.includes(id) ? v.filter(x => x !== id) : [...v, id]); }
  function addIngredients(recipe: Recipe) {
    setShopping(current => [...current, ...recipe.ingredients.filter(i => !current.some(s => s.name.toLowerCase() === i.name.toLowerCase())).map(i => ({ id: `${recipe.id}-${i.name}`, name: i.name, price: 2.5, checked: false }))]);
    setSelectedRecipe(null); setTab("budget");
  }
  function addPantry(form: FormData) {
    const name = String(form.get("name") || "").trim(); if (!name) return;
    setPantry(v => [...v, { id: crypto.randomUUID(), name, location: String(form.get("location")), quantity: Number(form.get("quantity")) || 1, expires: String(form.get("expires") || "") }]);
  }
  function addShop(form: FormData) {
    const name = String(form.get("name") || "").trim(); if (!name) return;
    setShopping(v => [...v, { id: crypto.randomUUID(), name, price: Number(form.get("price")) || 0, checked: false }]);
  }

  return <main className="app-shell">
    <header className="topbar">
      <div className="brand"><span className="brand-mark">t</span><div><strong>Thrive</strong><small>Save smarter. Eat better.</small></div></div>
      <div className="local-pill"><span /> Saved on this phone</div>
    </header>

    <section className="hero">
      <div><p className="eyebrow">YOUR FAMILY MONEY COMPANION</p><h1>Make every grocery dollar <em>go further.</em></h1><p>Find real savings, plan affordable meals, use what you already have, and stay on budget.</p></div>
      <div className="hero-card"><span>THIS WEEK</span><strong>{money(24.68)}</strong><p>potential savings found</p><div className="meter"><i /></div><small>That’s about 3 family dinners</small></div>
    </section>

    <nav className="desktop-nav" aria-label="Main navigation">{tabs.map(t => <button key={t.id} className={tab === t.id ? "active" : ""} onClick={() => { setTab(t.id); setQuery(""); }}><span>{t.icon}</span>{t.label}</button>)}</nav>

    <section className="content">
      {(tab === "savings" || tab === "recipes") && <div className="section-head"><div><p className="eyebrow">{tab === "savings" ? "TODAY’S BEST FINDS" : "AFFORDABLE FAMILY MEALS"}</p><h2>{tab === "savings" ? "Savings made simple" : "What sounds good?"}</h2></div><label className="search"><span>⌕</span><input aria-label={`Search ${tab}`} value={query} onChange={e => setQuery(e.target.value)} placeholder={`Search ${tab}…`} /></label></div>}

      {tab === "savings" && <div className="card-grid">{filteredCoupons.slice(0, 12).map((c, index) => { const discount = Math.round((1 - c.priceAfter / c.priceBefore) * 100); return <article className={`deal-card ${index === 0 ? "featured" : ""}`} key={c.id}><div className="card-top"><span className="store">{c.store}</span><button className="heart" aria-label="Favorite" onClick={() => toggleFavorite(c.id)}>{favorites.includes(c.id) ? "♥" : "♡"}</button></div><div className="deal-art">{c.category === "Dining" ? "🥡" : c.category === "Health" ? "🧴" : c.category === "Home" ? "🏠" : "🛒"}<b>{discount}% OFF</b></div><h3>{c.title}</h3><p>{c.description}</p><div className="price"><s>{money(c.priceBefore)}</s><strong>{money(c.priceAfter)}</strong><span>Ends in {c.endsInDays}d</span></div>{c.code && <button className="code" onClick={() => navigator.clipboard?.writeText(c.code || "")}>Copy code: {c.code}</button>}</article>})}</div>}

      {tab === "recipes" && <><div className="chips"><button>All meals</button><button>Under $10</button><button>20 minutes</button><button>5 ingredients</button><button>One pot</button></div><div className="recipe-grid">{filteredRecipes.map(r => <article className="recipe-card" key={r.id} onClick={() => setSelectedRecipe(r)}><div className="recipe-art">{r.tags.includes("mexican") ? "🌮" : r.tags.includes("asian") ? "🍜" : r.tags.includes("breakfast") ? "🥞" : r.tags.includes("seafood") ? "🐟" : "🍝"}</div><div><span className="tag">{r.section.replaceAll("_", " ")}</span><h3>{r.name}</h3><p>{r.description}</p><div className="recipe-meta"><span>⏱ {r.prepMinutes + r.cookMinutes} min</span><span>{money(r.costDollars / r.servings)}/serving</span></div></div></article>)}</div></>}

      {tab === "pantry" && <><div className="section-head"><div><p className="eyebrow">USE WHAT YOU HAVE</p><h2>Your pantry</h2><p>Add what’s at home and Thrive will find meals that fit.</p></div></div><div className="split"><section className="panel"><h3>Add an item</h3><form action={addPantry} className="form"><input name="name" required placeholder="e.g. black beans" /><div className="row"><select name="location"><option>Pantry</option><option>Fridge</option><option>Freezer</option></select><input name="quantity" type="number" min="1" defaultValue="1" /></div><input name="expires" type="date" aria-label="Expiration date"/><button>Add to pantry</button></form><div className="item-list">{pantry.length === 0 ? <p className="empty">Your pantry is ready for its first item.</p> : pantry.map(item => <div key={item.id}><span><b>{item.name}</b><small>{item.location} · Qty {item.quantity}</small></span><button onClick={() => setPantry(v => v.filter(x => x.id !== item.id))}>×</button></div>)}</div></section><section className="panel meal-panel"><span className="spark">✦ MAKE ME A MEAL</span><h3>Best matches from your kitchen</h3>{pantry.length === 0 ? <p className="empty">Add a few ingredients to see personalized meal ideas.</p> : mealMatches.map(({recipe, matches}) => <button className="meal-match" key={recipe.id} onClick={() => setSelectedRecipe(recipe)}><span>🍲</span><div><b>{recipe.name}</b><small>{matches} pantry ingredients · {money(recipe.costDollars)}</small></div><i>›</i></button>)}</section></div></>}

      {tab === "budget" && <><div className="section-head"><div><p className="eyebrow">SHOP WITH A PLAN</p><h2>Stay on budget</h2><p>Build your list and we’ll match it to the lowest available prices.</p></div></div><div className="budget-strip"><label>Weekly budget<input type="number" value={budget} onChange={e => setBudget(Number(e.target.value))}/></label><label>People<input type="number" min="1" value={people} onChange={e => setPeople(Number(e.target.value))}/></label><div><span>Estimated total</span><strong className={total > budget ? "over" : ""}>{money(total)}</strong><small>{money(Math.max(0, budget - total))} left</small></div></div><div className="split"><section className="panel"><h3>Shopping list</h3><form action={addShop} className="form inline"><input name="name" required placeholder="Add an item"/><input name="price" type="number" step=".01" placeholder="$"/><button>Add</button></form><div className="item-list">{shopping.length === 0 ? <p className="empty">Add groceries or send ingredients here from a recipe.</p> : shopping.map(item => <div key={item.id}><label><input type="checkbox" checked={item.checked} onChange={() => setShopping(v => v.map(x => x.id === item.id ? {...x, checked: !x.checked} : x))}/><span><b>{item.name}</b><small>Estimated {money(item.price)}</small></span></label><button onClick={() => setShopping(v => v.filter(x => x.id !== item.id))}>×</button></div>)}</div></section><section className="panel"><span className="spark">✦ BEST DEALS</span><h3>Lowest prices today</h3>{dealsData.slice(0, 5).map(d => <div className="deal-row" key={d.id}><span><b>{d.productName}</b><small>{d.store} · {d.unitPrice || d.size}</small></span><strong>{money(d.price)}</strong></div>)}</section></div></>}
    </section>

    <nav className="mobile-nav" aria-label="Main navigation">{tabs.map(t => <button key={t.id} className={tab === t.id ? "active" : ""} onClick={() => { setTab(t.id); setQuery(""); }}><span>{t.icon}</span>{t.label}</button>)}</nav>

    {selectedRecipe && <div className="modal-backdrop" onClick={() => setSelectedRecipe(null)}><article className="modal" onClick={e => e.stopPropagation()}><button className="close" onClick={() => setSelectedRecipe(null)}>×</button><span className="tag">{selectedRecipe.difficulty} · {selectedRecipe.prepMinutes + selectedRecipe.cookMinutes} minutes</span><h2>{selectedRecipe.name}</h2><p>{selectedRecipe.description}</p><div className="modal-grid"><div><h3>Ingredients</h3><ul>{selectedRecipe.ingredients.map(i => <li key={i.name}><b>{i.amount}</b> {i.name}</li>)}</ul></div><div><h3>Steps</h3><ol>{selectedRecipe.steps.map((s, i) => <li key={i}>{s}</li>)}</ol></div></div><button className="primary" onClick={() => addIngredients(selectedRecipe)}>Add ingredients to my list</button></article></div>}
  </main>;
}
