"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ArrowLeft, ArrowRight, CalendarDays, Check, ChefHat, ChevronRight,
  CircleDollarSign, Clock3, Heart, ListPlus, Minus,
  PackageOpen, Plus, RefreshCw, Refrigerator, Search, Settings, ShoppingCart, Sparkles,
  Tag, Trash2, WalletCards, X,
} from "lucide-react";
import couponsData from "./data/coupons.json";
import recipesData from "./data/recipes.json";
import dealsData from "./data/deals.json";
import catalogData from "./data/catalog.json";

type Tab = "savings" | "recipes" | "pantry" | "budget";
type Coupon = (typeof couponsData)[number];
type Recipe = (typeof recipesData)[number];
type PantryItem = { id: string; name: string; location: string; category: string; quantity: number };
type ShopItem = { id: string; name: string; category: string; quantity: number; price: number; checked: boolean };

const nav = [
  { id: "savings" as Tab, label: "Savings", Icon: Tag },
  { id: "recipes" as Tab, label: "Recipes", Icon: ChefHat },
  { id: "pantry" as Tab, label: "Pantry", Icon: Refrigerator },
  { id: "budget" as Tab, label: "Budget", Icon: WalletCards },
];
const sectionInfo: Record<string, [string, string]> = {
  under_10: ["Under $10", "Big flavor, tiny total"],
  under_20: ["Under 20 minutes", "Dinner before the delivery app loads"],
  five_ingredients: ["5 ingredients", "Less shopping. Less cleanup."],
  family_favorites: ["Family favorites", "The plates everyone clears"],
  one_pot: ["One pot", "Easy dinner, easier cleanup"],
};
const storageGroups = ["Fridge", "Freezer", "Pantry"];
const storeColors: Record<string, string> = { Kroger: "#1769aa", Aldi: "#ef6c00", Walmart: "#1976d2", Target: "#d32f2f", CVS: "#c62828", Amazon: "#232f3e" };
const money = (n: number) => new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(n);
const discount = (c: Coupon) => Math.floor((1 - c.priceAfter / c.priceBefore) * 100);
const initials = (name: string) => name.split(/\s+/).map(x => x[0]).join("").slice(0, 2).toUpperCase();
const foodImg = (seed?: string | null) => `https://picsum.photos/seed/thrive-food-${seed || "meal"}/600/600`;
const productImg = (seed?: string | null) => `https://picsum.photos/seed/thrive-${seed || "deal"}/600/600`;

export default function Home() {
  const [tab, setTab] = useState<Tab>("savings");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("All");
  const [favorites, setFavorites] = useState<string[]>([]);
  const [pantry, setPantry] = useState<PantryItem[]>([]);
  const [shopping, setShopping] = useState<ShopItem[]>([]);
  const [budget, setBudget] = useState<number | null>(null);
  const [people, setPeople] = useState(4);
  const [detail, setDetail] = useState<{ type: "coupon"; item: Coupon } | { type: "recipe"; item: Recipe } | null>(null);
  const [sheet, setSheet] = useState<"pantry" | "shopping" | "meal" | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    try {
      setFavorites(JSON.parse(localStorage.getItem("thrive-favorites") || "[]"));
      setPantry(JSON.parse(localStorage.getItem("thrive-pantry") || "[]"));
      setShopping(JSON.parse(localStorage.getItem("thrive-shopping") || "[]"));
      const savedBudget = localStorage.getItem("thrive-budget");
      if (savedBudget) setBudget(Number(savedBudget));
      const savedPeople = localStorage.getItem("thrive-people");
      if (savedPeople) setPeople(Number(savedPeople));
    } finally { setReady(true); }
  }, []);
  useEffect(() => { if (ready) localStorage.setItem("thrive-favorites", JSON.stringify(favorites)); }, [favorites, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-pantry", JSON.stringify(pantry)); }, [pantry, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-shopping", JSON.stringify(shopping)); }, [shopping, ready]);
  useEffect(() => { if (ready && budget !== null) localStorage.setItem("thrive-budget", String(budget)); }, [budget, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-people", String(people)); }, [people, ready]);

  const categories = ["All", ...Array.from(new Set(couponsData.map(c => c.category)))];
  const filteredCoupons = couponsData.filter(c => (category === "All" || c.category === category) && `${c.title} ${c.store} ${c.description}`.toLowerCase().includes(query.toLowerCase()));
  const recipeResults = recipesData.filter(r => `${r.name} ${r.tags.join(" ")} ${r.ingredients.map(i => i.name).join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  const featured = recipesData.filter(r => r.featured);
  const total = shopping.reduce((s, x) => s + x.price * x.quantity, 0);
  const mealMatches = useMemo(() => recipesData.map(recipe => ({ recipe, matches: recipe.ingredients.filter(i => pantry.some(p => i.name.toLowerCase().includes(p.name.toLowerCase()) || p.name.toLowerCase().includes(i.name.toLowerCase()))).length })).sort((a, b) => b.matches - a.matches).slice(0, 3), [pantry]);

  const toggleFavorite = (id: string) => setFavorites(v => v.includes(id) ? v.filter(x => x !== id) : [...v, id]);
  const switchTab = (next: Tab) => { setTab(next); setQuery(""); setCategory("All"); setDetail(null); window.scrollTo({ top: 0, behavior: "instant" }); };
  const addRecipe = (recipe: Recipe) => {
    setShopping(list => [...list, ...recipe.ingredients.filter(i => !list.some(x => x.name.toLowerCase() === i.name.toLowerCase())).map(i => ({ id: crypto.randomUUID(), name: i.name, category: "Grocery", quantity: 1, price: 2, checked: false }))]);
    setDetail(null); switchTab("budget");
  };

  return <div className="site-stage">
    <div className="desktop-note"><b>Thrive is made for your phone.</b><span>Open this page on iPhone or Android for the app experience.</span></div>
    <main className="phone-app">
      <div className="status-spacer" />
      <div className="screen">
        {tab === "savings" && <SavingsScreen query={query} setQuery={setQuery} category={category} setCategory={setCategory} categories={categories} coupons={filteredCoupons} favorites={favorites} toggleFavorite={toggleFavorite} open={item => setDetail({ type: "coupon", item })} />}
        {tab === "recipes" && <RecipesScreen query={query} setQuery={setQuery} results={recipeResults} featured={featured} favorites={favorites} toggleFavorite={toggleFavorite} open={item => setDetail({ type: "recipe", item })} />}
        {tab === "pantry" && <PantryScreen pantry={pantry} setPantry={setPantry} openAdd={() => setSheet("pantry")} openMeal={() => setSheet("meal")} openRecipe={item => setDetail({ type: "recipe", item })} mealMatches={mealMatches} />}
        {tab === "budget" && <BudgetScreen budget={budget} setBudget={setBudget} people={people} setPeople={setPeople} shopping={shopping} setShopping={setShopping} total={total} openAdd={() => setSheet("shopping")} />}
      </div>
      {!detail && <nav className="bottom-bar" aria-label="Main navigation">{nav.map(({ id, label, Icon }) => <button key={id} className={tab === id ? "selected" : ""} onClick={() => switchTab(id)}><span><Icon size={23} strokeWidth={tab === id ? 2.6 : 2} /></span><b>{label}</b></button>)}</nav>}
      {detail?.type === "coupon" && <CouponDetail coupon={detail.item} favorite={favorites.includes(detail.item.id)} back={() => setDetail(null)} toggle={() => toggleFavorite(detail.item.id)} />}
      {detail?.type === "recipe" && <RecipeDetail recipe={detail.item} favorite={favorites.includes(detail.item.id)} back={() => setDetail(null)} toggle={() => toggleFavorite(detail.item.id)} add={() => addRecipe(detail.item)} />}
      {sheet && <BottomSheet type={sheet} close={() => setSheet(null)} pantry={pantry} setPantry={setPantry} shopping={shopping} setShopping={setShopping} mealMatches={mealMatches} openRecipe={item => { setSheet(null); setDetail({ type: "recipe", item }); }} />}
    </main>
  </div>;
}

function SavingsScreen({ query, setQuery, category, setCategory, categories, coupons, favorites, toggleFavorite, open }: any) {
  const pick: Coupon = couponsData[0];
  const potential = coupons.reduce((sum: number, c: Coupon) => sum + c.priceBefore - c.priceAfter, 0);
  return <div className="scroll-screen">
    <header className="app-header savings-header"><div><div className="title-row"><h1 className="brand-title">Thrive</h1><span className="sync-chip">Offline feed</span></div><p>Good morning! Here&apos;s what&apos;s on sale today.</p></div><div className="header-actions"><button aria-label="Refresh"><RefreshCw /></button><button aria-label="Settings"><Settings /></button></div></header>
    <button className="daily-pick" onClick={() => open(pick)}><i /><div><span>TODAY&apos;S PICK</span><small>{pick.store}</small><h2>{pick.title}</h2></div><footer><div><strong>{money(pick.priceAfter)}</strong><small>was {money(pick.priceBefore)}</small></div><b>Save {discount(pick)}% <ArrowRight size={16}/></b></footer></button>
    <div className="savings-strip"><CircleDollarSign size={22}/><b>Save up to {money(potential)} this week across {coupons.length} deals</b></div>
    <SearchBox value={query} onChange={setQuery} placeholder="Search stores or products" />
    <div className="chip-row">{categories.map((c: string) => <button className={category === c ? "active" : ""} key={c} onClick={() => setCategory(c)}>{c}</button>)}</div>
    <p className="count-label">{coupons.length ? `${coupons.length} deals` : "No deals match"}</p>
    <div className="deal-list">{coupons.map((coupon: Coupon) => <DealRow key={coupon.id} coupon={coupon} favorite={favorites.includes(coupon.id)} toggle={() => toggleFavorite(coupon.id)} open={() => open(coupon)} />)}</div>
  </div>;
}

function DealRow({ coupon, favorite, toggle, open }: { coupon: Coupon; favorite: boolean; toggle: () => void; open: () => void }) {
  return <article className="deal-row" onClick={open}><div className="product-photo"><img src={productImg(coupon.imageSeed)} alt=""/><button aria-label="Favorite" onClick={e => { e.stopPropagation(); toggle(); }}><Heart size={17} fill={favorite ? "#ff5a3c" : "none"}/></button></div><div className="deal-copy"><div className="store-line"><span className="store-avatar" style={{ background: storeColors[coupon.store] || "#0d7c5f" }}>{initials(coupon.store)}</span><b>{coupon.store}</b>{coupon.isNew && <em>NEW</em>}{discount(coupon) >= 45 && coupon.endsInDays <= 3 && <em className="popular">POPULAR</em>}</div><h3>{coupon.title}</h3><div className="prices"><s>{money(coupon.priceBefore)}</s><strong>{money(coupon.priceAfter)}</strong><span>-{discount(coupon)}%</span></div><div className="micro-chips"><i>{coupon.endsInDays === 0 ? "Ends today" : `${coupon.endsInDays} days left`}</i><i>{coupon.dealType.replaceAll("_", " ")}</i></div></div></article>;
}

function RecipesScreen({ query, setQuery, results, featured, favorites, toggleFavorite, open }: any) {
  const sections = Object.keys(sectionInfo);
  return <div className="scroll-screen"><header className="app-header"><div><h1>Recipes</h1><p>Family meals that love your budget</p></div></header><SearchBox value={query} onChange={setQuery} placeholder="Search meals, tags, or ingredients" />
    {query ? <><SectionTitle title={results.length ? `${results.length} matches` : "No matches"}/><div className="recipe-results">{results.map((r: Recipe) => <RecipeSearchRow key={r.id} recipe={r} favorite={favorites.includes(r.id)} toggle={() => toggleFavorite(r.id)} open={() => open(r)}/>)}</div></> : <><div className="featured-scroll">{featured.map((r: Recipe) => <button className="featured-recipe" key={r.id} onClick={() => open(r)}><img src={foodImg(r.imageSeed)} alt=""/><span className="shade"/><div><em>★ Thrive pick</em><h3>{r.name}</h3><p>{r.prepMinutes + r.cookMinutes} min · {money(r.costDollars)} · {r.servings} servings</p></div></button>)}</div>{sections.map(key => { const recipes = recipesData.filter(r => r.section === key); return <section className="recipe-section" key={key}><SectionTitle title={sectionInfo[key][0]} subtitle={sectionInfo[key][1]}/><div className="recipe-scroll">{recipes.map(r => <button className="recipe-card" key={r.id} onClick={() => open(r)}><div><img src={foodImg(r.imageSeed)} alt=""/><span onClick={e => { e.stopPropagation(); toggleFavorite(r.id); }}><Heart size={16} fill={favorites.includes(r.id) ? "#ff5a3c" : "none"}/></span></div><h3>{r.name}</h3><p><span>{r.prepMinutes + r.cookMinutes}m</span><b>{money(r.costDollars)}</b><span>{r.servings} sv</span></p></button>)}</div></section>})}</>}
  </div>;
}

function PantryScreen({ pantry, setPantry, openAdd, openMeal, openRecipe, mealMatches }: any) {
  return <div className="scroll-screen"><header className="app-header"><div><h1>Pantry</h1><p>{pantry.length} item{pantry.length === 1 ? "" : "s"} stocked · all fresh</p></div></header>
    <button className={`feature-cta meal-cta ${!pantry.length ? "disabled" : ""}`} onClick={pantry.length ? openMeal : undefined}><span><Sparkles/></span><div><b>Make me a meal</b><small>{pantry.length ? "AI turns what you have into dinner" : "Add a few items to get started"}</small></div><ChevronRight/></button>
    <button className={`feature-cta week-cta ${!pantry.length ? "disabled" : ""}`}><span><CalendarDays/></span><div><b>Plan my week</b><small>{pantry.length ? "7 dinners under a weekly budget, from your pantry" : "Add a few items to get started"}</small></div><ChevronRight/></button>
    {pantry.length === 0 ? <div className="empty-state"><span><PackageOpen/></span><h2>Your kitchen is ready</h2><p>Add what&apos;s in your fridge, freezer, and pantry. Thrive will help you use it before it goes to waste.</p><button onClick={openAdd}><Plus size={18}/> Add your first item</button></div> : <>{storageGroups.map(group => { const items = pantry.filter((p: PantryItem) => p.location === group); if (!items.length) return null; return <section className="pantry-group" key={group}><SectionTitle title={group} subtitle={`${items.length} item${items.length === 1 ? "" : "s"}`}/>{items.map((item: PantryItem) => <div className="pantry-item" key={item.id}><span><PackageOpen size={19}/></span><div><b>{item.name}</b><small>{item.category}</small></div><div className="stepper"><button onClick={() => item.quantity === 1 ? setPantry((v: PantryItem[]) => v.filter(x => x.id !== item.id)) : setPantry((v: PantryItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity-1}:x))}><Minus/></button><b>{item.quantity}</b><button onClick={() => setPantry((v: PantryItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity+1}:x))}><Plus/></button></div></div>)}</section>})}</>}
    <button className="fab" onClick={openAdd} aria-label="Add pantry item"><Plus/></button>
  </div>;
}

function BudgetScreen({ budget, setBudget, people, setPeople, shopping, setShopping, total, openAdd }: any) {
  const [draft, setDraft] = useState("75");
  if (budget === null) return <div className="scroll-screen budget-onboarding"><header className="app-header"><div><h1>Budget</h1><p>Plan the trip, beat the store, keep the change.</p></div></header><div className="budget-hero"><h2>Let&apos;s plan your grocery trip</h2><p>Two quick questions, then we&apos;ll hunt down the best deals for your list.</p></div><section className="budget-form"><h2>How much can you spend?</h2><p>For this whole shopping trip</p><label className="money-input"><span>$</span><input inputMode="decimal" value={draft} onChange={e => setDraft(e.target.value.replace(/[^0-9.]/g, ""))}/></label><div className="amounts">{[40,75,100,150].map(n => <button key={n} className={draft === String(n) ? "active" : ""} onClick={() => setDraft(String(n))}>${n}</button>)}</div><h2>How many people?</h2><p>We&apos;ll estimate portions for your household</p><div className="people-stepper"><button onClick={() => setPeople(Math.max(1, people-1))}><Minus/></button><strong>{people}</strong><button onClick={() => setPeople(Math.min(12, people+1))}><Plus/></button></div><button className="primary-button" disabled={!Number(draft)} onClick={() => setBudget(Number(draft))}>Build my shopping list <ArrowRight size={18}/></button></section></div>;
  return <div className="scroll-screen"><header className="app-header"><div><h1>Budget</h1><p>Shopping for {people} · {money(budget)} budget</p></div><button className="icon-button"><Settings/></button></header><div className="budget-progress"><div><span>Estimated</span><strong>{money(total)}</strong></div><div><span>Budget</span><b>{money(budget)}</b></div><div className="progress-track"><i style={{ width: `${Math.min(100, total/budget*100)}%` }}/></div><p>{total <= budget ? `${money(budget-total)} left to spend` : `${money(total-budget)} over budget`}</p></div>{shopping.length === 0 ? <div className="empty-state"><span><ShoppingCart/></span><h2>Build your shopping list</h2><p>Add what you need — Thrive will find where it&apos;s cheapest.</p><button onClick={openAdd}><Plus size={18}/> Add items</button></div> : <section className="shopping-section"><SectionTitle title="Shopping list" subtitle={`${shopping.length} items`}/>{shopping.map((item: ShopItem) => <div className="shop-item" key={item.id}><button className={`check ${item.checked ? "done" : ""}`} onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, checked:!x.checked}:x))}>{item.checked && <Check/>}</button><div><b>{item.name}</b><small>{money(item.price)}</small></div><button className="trash" onClick={() => setShopping((v: ShopItem[]) => v.filter(x => x.id !== item.id))}><Trash2/></button><div className="stepper"><button onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, quantity:Math.max(1,x.quantity-1)}:x))}><Minus/></button><b>{item.quantity}</b><button onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity+1}:x))}><Plus/></button></div></div>)}<button className="deal-button"><Sparkles/> Find me the best deals</button></section>}<button className="fab" onClick={openAdd}><Plus/></button></div>;
}

function CouponDetail({ coupon, favorite, back, toggle }: any) { return <section className="detail-screen"><div className="detail-photo"><img src={productImg(coupon.imageSeed)} alt=""/><button onClick={back}><ArrowLeft/></button><button onClick={toggle}><Heart fill={favorite ? "#ff5a3c" : "white"}/></button></div><div className="detail-body"><div className="store-line"><span className="store-avatar" style={{background:storeColors[coupon.store] || "#0d7c5f"}}>{initials(coupon.store)}</span><b>{coupon.store}</b></div><h1>{coupon.title}</h1><p>{coupon.description}</p><div className="detail-price"><div><s>{money(coupon.priceBefore)}</s><strong>{money(coupon.priceAfter)}</strong></div><span>Save {discount(coupon)}%</span></div><div className="info-box"><Clock3/><span><b>{coupon.endsInDays} days left</b><small>{coupon.terms || "Offer terms may apply."}</small></span></div>{coupon.code ? <button className="primary-button" onClick={() => navigator.clipboard?.writeText(coupon.code || "")}>Copy code · {coupon.code}</button> : <button className="primary-button">Get this deal <ArrowRight/></button>}</div></section> }

function RecipeDetail({ recipe, favorite, back, toggle, add }: any) { return <section className="detail-screen recipe-detail"><div className="detail-photo"><img src={foodImg(recipe.imageSeed)} alt=""/><button onClick={back}><ArrowLeft/></button><button onClick={toggle}><Heart fill={favorite ? "#ff5a3c" : "white"}/></button></div><div className="detail-body"><span className="soft-label">{recipe.difficulty}</span><h1>{recipe.name}</h1><p>{recipe.description}</p><div className="recipe-stats"><span><Clock3/> <b>{recipe.prepMinutes + recipe.cookMinutes} min</b></span><span><ChefHat/> <b>{recipe.servings} servings</b></span><span><CircleDollarSign/> <b>{money(recipe.costDollars / recipe.servings)}/serving</b></span></div><SectionTitle title="Ingredients"/><ul className="ingredients">{recipe.ingredients.map((i: any) => <li key={i.name}><span>{i.amount}</span><b>{i.name}</b></li>)}</ul><button className="list-button" onClick={add}><ListPlus/> Add to shopping list</button><SectionTitle title="Let’s cook"/>{recipe.steps.map((s: string, i: number) => <div className="cook-step" key={i}><span>{i+1}</span><p>{s}</p></div>)}</div></section> }

function BottomSheet({ type, close, pantry, setPantry, shopping, setShopping, mealMatches, openRecipe }: any) {
  const [search, setSearch] = useState("");
  const options = catalogData.filter(x => x.name.toLowerCase().includes(search.toLowerCase())).slice(0, 18);
  return <div className="sheet-backdrop" onClick={close}><section className="bottom-sheet" onClick={e => e.stopPropagation()}><i className="handle"/><button className="sheet-close" onClick={close}><X/></button>{type === "meal" ? <><h2>Make me a meal</h2><p>Best matches from what&apos;s already in your kitchen.</p><div className="meal-options">{mealMatches.map(({recipe,matches}: any) => <button key={recipe.id} onClick={() => openRecipe(recipe)}><img src={foodImg(recipe.imageSeed)} alt=""/><div><b>{recipe.name}</b><small>{matches} pantry matches · {money(recipe.costDollars)}</small></div><ChevronRight/></button>)}</div></> : <><h2>{type === "pantry" ? "Add to pantry" : "Add to shopping list"}</h2><SearchBox value={search} onChange={setSearch} placeholder="Search items…"/><div className="catalog-list">{options.map(item => <button key={item.name} onClick={() => { if (type === "pantry") setPantry((v: PantryItem[]) => [...v,{id:crypto.randomUUID(),name:item.name,location:item.location,category:item.category,quantity:1}]); else setShopping((v: ShopItem[]) => [...v,{id:crypto.randomUUID(),name:item.name,category:item.category,quantity:1,price:item.defaultPrice || 2,checked:false}]); close(); }}><span><PackageOpen/></span><div><b>{item.name}</b><small>{item.category}{type === "shopping" ? ` · ${money(item.defaultPrice || 2)}` : ` · ${item.location}`}</small></div><Plus/></button>)}</div></>}</section></div>;
}

function SearchBox({ value, onChange, placeholder }: any) { return <label className="search-box"><Search/><input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}/></label> }
function SectionTitle({ title, subtitle }: { title: string; subtitle?: string }) { return <div className="section-title"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div></div> }
function RecipeSearchRow({ recipe, favorite, toggle, open }: any) { return <button className="recipe-search-row" onClick={open}><img src={foodImg(recipe.imageSeed)} alt=""/><div><b>{recipe.name}</b><small>{recipe.prepMinutes + recipe.cookMinutes} min · {money(recipe.costDollars)} · {recipe.servings} servings</small></div><span onClick={e => {e.stopPropagation();toggle();}}><Heart fill={favorite ? "#ff5a3c" : "none"}/></span></button> }
