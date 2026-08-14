"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ArrowLeft, ArrowRight, CalendarDays, Check, ChefHat, ChevronRight, Cloud,
  CloudOff, CircleDollarSign, Clock3, Download, Heart, Info, ListPlus, Minus,
  PackageOpen, Plus, RefreshCw, Refrigerator, RotateCcw, Search, Settings, ShoppingCart, Sparkles,
  Store, Tag, Trash2, WalletCards, X,
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
  const [sheet, setSheet] = useState<"pantry" | "shopping" | "meal" | "week" | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [syncStatus, setSyncStatus] = useState<"syncing" | "live" | "offline" | "saved">("syncing");
  const [deviceId, setDeviceId] = useState("");
  const [serverSuggestions, setServerSuggestions] = useState<any[]>([]);
  const [couponsFeed, setCouponsFeed] = useState<Coupon[]>(couponsData);
  const [recipesFeed, setRecipesFeed] = useState<Recipe[]>(recipesData);
  const [catalogFeed, setCatalogFeed] = useState<any[]>(catalogData);
  const [weekly, setWeekly] = useState<any>(null);
  const [trip, setTrip] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled=false;
    (async()=>{
      let id=localStorage.getItem("thrive-device-id");
      if(!id){id=crypto.randomUUID();localStorage.setItem("thrive-device-id",id)}
      setDeviceId(id);
      setFavorites(JSON.parse(localStorage.getItem("thrive-favorites") || "[]"));
      setPantry(JSON.parse(localStorage.getItem("thrive-pantry") || "[]"));
      setShopping(JSON.parse(localStorage.getItem("thrive-shopping") || "[]"));
      const savedBudget=localStorage.getItem("thrive-budget"),savedPeople=localStorage.getItem("thrive-people");
      if(savedBudget)setBudget(Number(savedBudget)); if(savedPeople)setPeople(Number(savedPeople));
      try{
        const [feed,stateResponse]=await Promise.all([fetch("/api/v1/sync",{cache:"no-store"}),fetch(`/api/v1/state?deviceId=${encodeURIComponent(id)}`,{cache:"no-store"})]);
        if(feed.ok){const live=await feed.json();if(Array.isArray(live.coupons))setCouponsFeed(live.coupons);if(Array.isArray(live.recipes))setRecipesFeed(live.recipes);if(Array.isArray(live.catalog))setCatalogFeed(live.catalog);setSyncStatus("live");}
        if(stateResponse.ok){const saved=await stateResponse.json();if(saved.found&&saved.state){setFavorites(saved.state.favorites||[]);setPantry(saved.state.pantry||[]);setShopping(saved.state.shopping||[]);if(saved.state.budget!==null)setBudget(saved.state.budget);setPeople(saved.state.people||4);setSyncStatus("saved")}}
      }catch{setSyncStatus("offline")}
      if(!cancelled)setReady(true);
    })();
    return()=>{cancelled=true};
  }, []);
  useEffect(() => { if (ready) localStorage.setItem("thrive-favorites", JSON.stringify(favorites)); }, [favorites, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-pantry", JSON.stringify(pantry)); }, [pantry, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-shopping", JSON.stringify(shopping)); }, [shopping, ready]);
  useEffect(() => { if (ready && budget !== null) localStorage.setItem("thrive-budget", String(budget)); }, [budget, ready]);
  useEffect(() => { if (ready) localStorage.setItem("thrive-people", String(people)); }, [people, ready]);
  useEffect(()=>{if(!ready||!deviceId)return;const timer=setTimeout(async()=>{try{const response=await fetch("/api/v1/state",{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({deviceId,state:{favorites,pantry,shopping,budget,people,settings:{}}})});if(response.ok)setSyncStatus("saved")}catch{setSyncStatus("offline")}},650);return()=>clearTimeout(timer)},[ready,deviceId,favorites,pantry,shopping,budget,people]);

  const categories = ["All", ...Array.from(new Set(couponsFeed.map(c => c.category)))];
  const filteredCoupons = couponsFeed.filter(c => (category === "All" || c.category === category) && `${c.title} ${c.store} ${c.description}`.toLowerCase().includes(query.toLowerCase()));
  const recipeResults = recipesFeed.filter(r => `${r.name} ${r.tags.join(" ")} ${r.ingredients.map(i => i.name).join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  const featured = recipesFeed.filter(r => r.featured);
  const total = shopping.reduce((s, x) => s + x.price * x.quantity, 0);
  const mealMatches = useMemo(() => recipesFeed.map(recipe => ({ recipe, matches: recipe.ingredients.filter(i => pantry.some(p => i.name.toLowerCase().includes(p.name.toLowerCase()) || p.name.toLowerCase().includes(i.name.toLowerCase()))).length })).sort((a, b) => b.matches - a.matches).slice(0, 3), [pantry, recipesFeed]);

  const toggleFavorite = (id: string) => setFavorites(v => v.includes(id) ? v.filter(x => x !== id) : [...v, id]);
  const switchTab = (next: Tab) => { setTab(next); setQuery(""); setCategory("All"); setDetail(null); window.scrollTo({ top: 0, behavior: "instant" }); };
  const addRecipe = (recipe: Recipe) => {
    setShopping(list => [...list, ...recipe.ingredients.filter(i => !list.some(x => x.name.toLowerCase() === i.name.toLowerCase())).map(i => ({ id: crypto.randomUUID(), name: i.name, category: "Grocery", quantity: 1, price: 2, checked: false }))]);
    setDetail(null); switchTab("budget");
  };
  const refreshSync=async()=>{setSyncStatus("syncing");try{const response=await fetch("/api/v1/sync",{cache:"no-store"});if(!response.ok)throw new Error("sync failed");const live=await response.json();if(Array.isArray(live.coupons))setCouponsFeed(live.coupons);if(Array.isArray(live.recipes))setRecipesFeed(live.recipes);if(Array.isArray(live.catalog))setCatalogFeed(live.catalog);setSyncStatus("live")}catch{setSyncStatus("offline")}};
  const openMeals=async()=>{setSheet("meal");setBusy(true);try{const response=await fetch("/api/v1/meal-suggestions",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({pantry,limit:3})});if(response.ok){const data=await response.json();setServerSuggestions(data.suggestions||[])}}finally{setBusy(false)}};
  const buildWeek=async(input:{budget:number;people:number;focus:string})=>{setBusy(true);try{const response=await fetch("/api/v1/weekly-plan",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({pantry,...input,nights:7})});if(response.ok){const data=await response.json();setWeekly(data.plan);setSheet(null)}}finally{setBusy(false)}};
  const findDeals=async()=>{if(budget===null||!shopping.length)return;setBusy(true);try{const response=await fetch("/api/v1/trip-plan",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({items:shopping,budget,people})});if(response.ok){const data=await response.json();setTrip(data.plan)}}finally{setBusy(false)}};
  const clearAll=async()=>{setFavorites([]);setPantry([]);setShopping([]);setBudget(null);setWeekly(null);setTrip(null);["thrive-favorites","thrive-pantry","thrive-shopping","thrive-budget"].forEach(k=>localStorage.removeItem(k));if(deviceId)await fetch(`/api/v1/state?deviceId=${encodeURIComponent(deviceId)}`,{method:"DELETE"}).catch(()=>{});setSettingsOpen(false)};
  const exportData=()=>{const blob=new Blob([JSON.stringify({favorites,pantry,shopping,budget,people,exportedAt:new Date().toISOString()},null,2)],{type:"application/json"});const url=URL.createObjectURL(blob),a=document.createElement("a");a.href=url;a.download="thrive-data.json";a.click();URL.revokeObjectURL(url)};

  return <div className="site-stage">
    <div className="desktop-note"><b>Thrive is made for your phone.</b><span>Open this page on iPhone or Android for the app experience.</span></div>
    <main className="phone-app">
      <div className="status-spacer" />
      <div className="screen">
        {tab === "savings" && <SavingsScreen query={query} setQuery={setQuery} category={category} setCategory={setCategory} categories={categories} coupons={filteredCoupons} favorites={favorites} toggleFavorite={toggleFavorite} open={item => setDetail({ type: "coupon", item })} syncStatus={syncStatus} refresh={refreshSync} settings={()=>setSettingsOpen(true)} />}
        {tab === "recipes" && <RecipesScreen query={query} setQuery={setQuery} results={recipeResults} featured={featured} favorites={favorites} toggleFavorite={toggleFavorite} open={item => setDetail({ type: "recipe", item })} />}
        {tab === "pantry" && <PantryScreen pantry={pantry} setPantry={setPantry} openAdd={() => setSheet("pantry")} openMeal={openMeals} openWeek={()=>setSheet("week")} openRecipe={item => setDetail({ type: "recipe", item })} mealMatches={mealMatches} />}
        {tab === "budget" && <BudgetScreen budget={budget} setBudget={setBudget} people={people} setPeople={setPeople} shopping={shopping} setShopping={setShopping} total={total} openAdd={() => setSheet("shopping")} settings={()=>setSettingsOpen(true)} findDeals={findDeals} busy={busy} trip={trip} backFromTrip={()=>setTrip(null)} />}
      </div>
      {!detail && <nav className="bottom-bar" aria-label="Main navigation">{nav.map(({ id, label, Icon }) => <button key={id} className={tab === id ? "selected" : ""} onClick={() => switchTab(id)}><span><Icon size={23} strokeWidth={tab === id ? 2.6 : 2} /></span><b>{label}</b></button>)}</nav>}
      {detail?.type === "coupon" && <CouponDetail coupon={detail.item} favorite={favorites.includes(detail.item.id)} back={() => setDetail(null)} toggle={() => toggleFavorite(detail.item.id)} />}
      {detail?.type === "recipe" && <RecipeDetail recipe={detail.item} favorite={favorites.includes(detail.item.id)} back={() => setDetail(null)} toggle={() => toggleFavorite(detail.item.id)} add={() => addRecipe(detail.item)} />}
      {sheet && <BottomSheet type={sheet} close={() => setSheet(null)} pantry={pantry} setPantry={setPantry} shopping={shopping} setShopping={setShopping} catalog={catalogFeed} mealMatches={serverSuggestions.length ? serverSuggestions : mealMatches.map(x => ({recipe:x.recipe,usesCount:x.matches,estimatedExtraCost:x.recipe.costDollars,missingItems:[],usedItems:[],aiTip:"Based on what is in your pantry."}))} openRecipe={item => { setSheet(null); setDetail({ type: "recipe", item }); }} busy={busy} planWeek={buildWeek} budget={budget} people={people} />}
      {weekly && <WeeklyPlanScreen plan={weekly} close={()=>setWeekly(null)} addAll={()=>{setShopping(list=>[...list,...weekly.combinedShopping.filter((m:any)=>!list.some(x=>x.name.toLowerCase()===m.name.toLowerCase())).map((m:any)=>({id:crypto.randomUUID(),name:m.name,category:"Grocery",quantity:1,price:m.estCost,checked:false}))]);setWeekly(null);switchTab("budget")}} openRecipe={(recipe:Recipe)=>{setWeekly(null);setDetail({type:"recipe",item:recipe})}} />}
      {settingsOpen && <SettingsScreen status={syncStatus} close={()=>setSettingsOpen(false)} sync={refreshSync} exportData={exportData} clearAll={clearAll} deviceId={deviceId} />}
    </main>
  </div>;
}

function SavingsScreen({ query, setQuery, category, setCategory, categories, coupons, favorites, toggleFavorite, open, syncStatus, refresh, settings }: any) {
  const pick: Coupon = coupons[0] || couponsData[0];
  const potential = coupons.reduce((sum: number, c: Coupon) => sum + c.priceBefore - c.priceAfter, 0);
  const statusLabel = syncStatus === "offline" ? "Offline" : syncStatus === "syncing" ? "Syncing" : syncStatus === "saved" ? "Saved" : "Live deals";
  return <div className="scroll-screen">
    <header className="app-header savings-header"><div><div className="title-row"><h1 className="brand-title">Thrive</h1><span className={`sync-chip ${syncStatus}`}>{syncStatus === "offline" ? <CloudOff size={11}/> : <Cloud size={11}/>} {statusLabel}</span></div><p>Good morning! Here&apos;s what&apos;s on sale today.</p></div><div className="header-actions"><button aria-label="Refresh deals" onClick={refresh}><RefreshCw className={syncStatus === "syncing" ? "spinning" : ""}/></button><button aria-label="Settings" onClick={settings}><Settings /></button></div></header>
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

function PantryScreen({ pantry, setPantry, openAdd, openMeal, openWeek }: any) {
  return <div className="scroll-screen"><header className="app-header"><div><h1>Pantry</h1><p>{pantry.length} item{pantry.length === 1 ? "" : "s"} stocked · all fresh</p></div></header>
    <button className={`feature-cta meal-cta ${!pantry.length ? "disabled" : ""}`} onClick={pantry.length ? openMeal : undefined}><span><Sparkles/></span><div><b>Make me a meal</b><small>{pantry.length ? "AI turns what you have into dinner" : "Add a few items to get started"}</small></div><ChevronRight/></button>
    <button className={`feature-cta week-cta ${!pantry.length ? "disabled" : ""}`} onClick={pantry.length ? openWeek : undefined}><span><CalendarDays/></span><div><b>Plan my week</b><small>{pantry.length ? "7 dinners under a weekly budget, from your pantry" : "Add a few items to get started"}</small></div><ChevronRight/></button>
    {pantry.length === 0 ? <div className="empty-state"><span><PackageOpen/></span><h2>Your kitchen is ready</h2><p>Add what&apos;s in your fridge, freezer, and pantry. Thrive will help you use it before it goes to waste.</p><button onClick={openAdd}><Plus size={18}/> Add your first item</button></div> : <>{storageGroups.map(group => { const items = pantry.filter((p: PantryItem) => p.location === group); if (!items.length) return null; return <section className="pantry-group" key={group}><SectionTitle title={group} subtitle={`${items.length} item${items.length === 1 ? "" : "s"}`}/>{items.map((item: PantryItem) => <div className="pantry-item" key={item.id}><span><PackageOpen size={19}/></span><div><b>{item.name}</b><small>{item.category}</small></div><div className="stepper"><button onClick={() => item.quantity === 1 ? setPantry((v: PantryItem[]) => v.filter(x => x.id !== item.id)) : setPantry((v: PantryItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity-1}:x))}><Minus/></button><b>{item.quantity}</b><button onClick={() => setPantry((v: PantryItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity+1}:x))}><Plus/></button></div></div>)}</section>})}</>}
    <button className="fab" onClick={openAdd} aria-label="Add pantry item"><Plus/></button>
  </div>;
}

function BudgetScreen({ budget, setBudget, people, setPeople, shopping, setShopping, total, openAdd, settings, findDeals, busy, trip, backFromTrip }: any) {
  const [draft, setDraft] = useState("75");
  if (trip) return <TripPlanScreen plan={trip} close={backFromTrip} />;
  if (budget === null) return <div className="scroll-screen budget-onboarding"><header className="app-header"><div><h1>Budget</h1><p>Plan the trip, beat the store, keep the change.</p></div></header><div className="budget-hero"><h2>Let&apos;s plan your grocery trip</h2><p>Two quick questions, then we&apos;ll hunt down the best deals for your list.</p></div><section className="budget-form"><h2>How much can you spend?</h2><p>For this whole shopping trip</p><label className="money-input"><span>$</span><input inputMode="decimal" value={draft} onChange={e => setDraft(e.target.value.replace(/[^0-9.]/g, ""))}/></label><div className="amounts">{[40,75,100,150].map(n => <button key={n} className={draft === String(n) ? "active" : ""} onClick={() => setDraft(String(n))}>${n}</button>)}</div><h2>How many people?</h2><p>We&apos;ll estimate portions for your household</p><div className="people-stepper"><button onClick={() => setPeople(Math.max(1, people-1))}><Minus/></button><strong>{people}</strong><button onClick={() => setPeople(Math.min(12, people+1))}><Plus/></button></div><button className="primary-button" disabled={!Number(draft)} onClick={() => setBudget(Number(draft))}>Build my shopping list <ArrowRight size={18}/></button></section></div>;
  return <div className="scroll-screen"><header className="app-header"><div><h1>Budget</h1><p>Shopping for {people} · {money(budget)} budget</p></div><button className="icon-button" aria-label="Settings" onClick={settings}><Settings/></button></header><div className="budget-progress"><div><span>Estimated</span><strong>{money(total)}</strong></div><div><span>Budget</span><b>{money(budget)}</b></div><div className="progress-track"><i style={{ width: `${Math.min(100, total/budget*100)}%` }}/></div><p>{total <= budget ? `${money(budget-total)} left to spend` : `${money(total-budget)} over budget`}</p></div>{shopping.length === 0 ? <div className="empty-state"><span><ShoppingCart/></span><h2>Build your shopping list</h2><p>Add what you need — Thrive will find where it&apos;s cheapest.</p><button onClick={openAdd}><Plus size={18}/> Add items</button></div> : <section className="shopping-section"><SectionTitle title="Shopping list" subtitle={`${shopping.length} items`}/>{shopping.map((item: ShopItem) => <div className="shop-item" key={item.id}><button className={`check ${item.checked ? "done" : ""}`} onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, checked:!x.checked}:x))}>{item.checked && <Check/>}</button><div><b>{item.name}</b><small>{money(item.price)}</small></div><button className="trash" onClick={() => setShopping((v: ShopItem[]) => v.filter(x => x.id !== item.id))}><Trash2/></button><div className="stepper"><button onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, quantity:Math.max(1,x.quantity-1)}:x))}><Minus/></button><b>{item.quantity}</b><button onClick={() => setShopping((v: ShopItem[]) => v.map(x => x.id === item.id ? {...x, quantity:x.quantity+1}:x))}><Plus/></button></div></div>)}<button className="deal-button" onClick={findDeals} disabled={busy}>{busy ? <><RefreshCw className="spinning"/> Checking deals…</> : <><Sparkles/> Find me the best deals</>}</button></section>}<button className="fab" onClick={openAdd}><Plus/></button></div>;
}

function CouponDetail({ coupon, favorite, back, toggle }: any) { return <section className="detail-screen"><div className="detail-photo"><img src={productImg(coupon.imageSeed)} alt=""/><button onClick={back}><ArrowLeft/></button><button onClick={toggle}><Heart fill={favorite ? "#ff5a3c" : "white"}/></button></div><div className="detail-body"><div className="store-line"><span className="store-avatar" style={{background:storeColors[coupon.store] || "#0d7c5f"}}>{initials(coupon.store)}</span><b>{coupon.store}</b></div><h1>{coupon.title}</h1><p>{coupon.description}</p><div className="detail-price"><div><s>{money(coupon.priceBefore)}</s><strong>{money(coupon.priceAfter)}</strong></div><span>Save {discount(coupon)}%</span></div><div className="info-box"><Clock3/><span><b>{coupon.endsInDays} days left</b><small>{coupon.terms || "Offer terms may apply."}</small></span></div>{coupon.code ? <button className="primary-button" onClick={() => navigator.clipboard?.writeText(coupon.code || "")}>Copy code · {coupon.code}</button> : <button className="primary-button">Get this deal <ArrowRight/></button>}</div></section> }

function RecipeDetail({ recipe, favorite, back, toggle, add }: any) { return <section className="detail-screen recipe-detail"><div className="detail-photo"><img src={foodImg(recipe.imageSeed)} alt=""/><button onClick={back}><ArrowLeft/></button><button onClick={toggle}><Heart fill={favorite ? "#ff5a3c" : "white"}/></button></div><div className="detail-body"><span className="soft-label">{recipe.difficulty}</span><h1>{recipe.name}</h1><p>{recipe.description}</p><div className="recipe-stats"><span><Clock3/> <b>{recipe.prepMinutes + recipe.cookMinutes} min</b></span><span><ChefHat/> <b>{recipe.servings} servings</b></span><span><CircleDollarSign/> <b>{money(recipe.costDollars / recipe.servings)}/serving</b></span></div><SectionTitle title="Ingredients"/><ul className="ingredients">{recipe.ingredients.map((i: any) => <li key={i.name}><span>{i.amount}</span><b>{i.name}</b></li>)}</ul><button className="list-button" onClick={add}><ListPlus/> Add to shopping list</button><SectionTitle title="Let’s cook"/>{recipe.steps.map((s: string, i: number) => <div className="cook-step" key={i}><span>{i+1}</span><p>{s}</p></div>)}</div></section> }

function BottomSheet({ type, close, pantry, setPantry, shopping, setShopping, catalog, mealMatches, openRecipe, busy, planWeek, budget, people }: any) {
  const [search, setSearch] = useState("");
  const [weekBudget, setWeekBudget] = useState(String(budget || 75));
  const [weekPeople, setWeekPeople] = useState(people || 4);
  const [focus, setFocus] = useState("balanced");
  const options = catalog.filter((x: any) => x.name.toLowerCase().includes(search.toLowerCase())).slice(0, 18);
  let content;
  if (type === "meal") content = <><h2>Make me a meal</h2><p>Best matches calculated from what&apos;s already in your kitchen.</p>{busy ? <div className="sheet-loading"><RefreshCw className="spinning"/><b>Matching your pantry…</b></div> : <div className="meal-options">{mealMatches.map(({recipe,usesCount,estimatedExtraCost,aiTip}: any) => <button key={recipe.id} onClick={() => openRecipe(recipe)}><img src={foodImg(recipe.imageSeed)} alt=""/><div><b>{recipe.name}</b><small>{usesCount || 0} pantry matches · {money(estimatedExtraCost || 0)} more</small><em>{aiTip}</em></div><ChevronRight/></button>)}</div>}</>;
  else if (type === "week") content = <><h2>Plan my week</h2><p>Build seven dinners around your pantry and spending limit.</p><div className="week-form"><label>Weekly dinner budget<span className="money-input"><b>$</b><input inputMode="decimal" value={weekBudget} onChange={e => setWeekBudget(e.target.value.replace(/[^0-9.]/g,""))}/></span></label><label>People<div className="people-stepper"><button onClick={() => setWeekPeople(Math.max(1,weekPeople-1))}><Minus/></button><strong>{weekPeople}</strong><button onClick={() => setWeekPeople(Math.min(12,weekPeople+1))}><Plus/></button></div></label><label>Priority<div className="focus-grid">{[["balanced","Balanced"],["use_expiring","Use food soon"],["lowest_cost","Lowest cost"]].map(([id,label]) => <button key={id} className={focus === id ? "active" : ""} onClick={() => setFocus(id)}>{label}</button>)}</div></label><button className="primary-button" disabled={busy || !Number(weekBudget)} onClick={() => planWeek({budget:Number(weekBudget),people:weekPeople,focus})}>{busy ? <><RefreshCw className="spinning"/> Building plan…</> : <><CalendarDays/> Build 7-night plan</>}</button></div></>;
  else content = <><h2>{type === "pantry" ? "Add to pantry" : "Add to shopping list"}</h2><SearchBox value={search} onChange={setSearch} placeholder="Search items…"/><div className="catalog-list">{options.map((item: any) => <button key={item.name} onClick={() => { if (type === "pantry") setPantry((v: PantryItem[]) => [...v,{id:crypto.randomUUID(),name:item.name,location:item.location,category:item.category,quantity:1}]); else setShopping((v: ShopItem[]) => [...v,{id:crypto.randomUUID(),name:item.name,category:item.category,quantity:1,price:item.defaultPrice || 2,checked:false}]); close(); }}><span><PackageOpen/></span><div><b>{item.name}</b><small>{item.category}{type === "shopping" ? ` · ${money(item.defaultPrice || 2)}` : ` · ${item.location}`}</small></div><Plus/></button>)}</div></>;
  return <div className="sheet-backdrop" onClick={close}><section className="bottom-sheet" onClick={e => e.stopPropagation()}><i className="handle"/><button className="sheet-close" onClick={close}><X/></button>{content}</section></div>;
}

function WeeklyPlanScreen({ plan, close, addAll, openRecipe }: any) {
  return <section className="overlay-screen"><header className="overlay-header"><button onClick={close}><ArrowLeft/></button><div><h1>Your week</h1><p>{plan.nightsCount} dinners for {plan.people}</p></div></header><div className={`plan-summary ${plan.underBudget ? "good" : "over"}`}><div><span>Estimated total</span><strong>{money(plan.totalCost)}</strong></div><div><span>{plan.underBudget ? "Left" : "Over"}</span><b>{money(plan.underBudget ? plan.remaining : plan.overshoot)}</b></div><p>{plan.aiTip}</p></div><div className="night-list">{plan.nights.map(({day,suggestion}: any) => <button key={day} onClick={() => openRecipe(suggestion.recipe)}><span>{day}</span><img src={foodImg(suggestion.recipe.imageSeed)} alt=""/><div><b>{suggestion.recipe.name}</b><small>{suggestion.usesCount} pantry matches · {money(suggestion.estimatedExtraCost)} extra</small></div><ChevronRight/></button>)}</div>{plan.combinedShopping.length ? <div className="sticky-action"><button className="primary-button" onClick={addAll}><ListPlus/> Add {plan.combinedShopping.length} missing items</button></div> : <div className="sticky-action"><button className="primary-button" onClick={close}><Check/> Pantry has you covered</button></div>}</section>;
}

function TripPlanScreen({ plan, close }: any) {
  return <div className="trip-plan"><header className="app-header"><button className="back-button" onClick={close}><ArrowLeft/></button><div><h1>Best deal plan</h1><p>{plan.items.length} items · {plan.storesUsed.length} store{plan.storesUsed.length === 1 ? "" : "s"}</p></div></header><div className={`trip-summary ${plan.status === "UNDER_BUDGET" ? "good" : "over"}`}><div><span>New estimate</span><strong>{money(plan.totalAfter)}</strong></div><div><span>You save</span><b>{money(plan.totalSavings)}</b></div><p>{plan.aiInsights}</p></div>{plan.storesUsed.map(([store,total]: [string,number]) => <section className="store-group" key={store}><SectionTitle title={store} subtitle={money(total)}/>{plan.items.filter((x:any)=>x.store===store).map((row:any)=><div className="deal-match" key={row.item.id}><span className="store-avatar" style={{background:storeColors[store]||"#0d7c5f"}}>{initials(store)}</span><div><b>{row.item.name}</b><small>{row.dealFound ? `Deal found · save ${money(row.savings)}` : "No lower match today"}</small></div><strong>{money(row.price*row.item.quantity)}</strong></div>)}</section>)}</div>;
}

function SettingsScreen({ status, close, sync, exportData, clearAll, deviceId }: any) {
  const live = status !== "offline";
  return <section className="overlay-screen settings-screen"><header className="overlay-header"><button onClick={close}><ArrowLeft/></button><div><h1>Settings</h1><p>Data and connection</p></div></header><div className="settings-card"><span className={live ? "online" : "offline"}>{live ? <Cloud/> : <CloudOff/>}</span><div><b>{live ? "Cloud backup is on" : "Working offline"}</b><small>{live ? "Changes save automatically on this device." : "Changes remain safely on this phone."}</small></div><button onClick={sync}><RefreshCw className={status === "syncing" ? "spinning" : ""}/></button></div><section className="settings-list"><button onClick={sync}><span><RotateCcw/></span><div><b>Sync now</b><small>Refresh deals and save your latest changes</small></div><ChevronRight/></button><button onClick={exportData}><span><Download/></span><div><b>Export my data</b><small>Download pantry, favorites, budget, and list</small></div><ChevronRight/></button><div className="settings-info"><span><Info/></span><div><b>No account required</b><small>Your private backup key stays in this browser: {deviceId ? `${deviceId.slice(0,8)}…` : "creating…"}</small></div></div><button className="danger-row" onClick={() => { if (window.confirm("Clear all Thrive data from this phone and its cloud backup?")) clearAll(); }}><span><Trash2/></span><div><b>Clear all data</b><small>This removes saved pantry, lists, budget, and favorites</small></div><ChevronRight/></button></section><div className="privacy-note"><Store/><p>Deal comparisons use Thrive&apos;s current daily feed. Retailer checkout and final prices stay with each store.</p></div></section>;
}

function SearchBox({ value, onChange, placeholder }: any) { return <label className="search-box"><Search/><input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}/></label> }
function SectionTitle({ title, subtitle }: { title: string; subtitle?: string }) { return <div className="section-title"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div></div> }
function RecipeSearchRow({ recipe, favorite, toggle, open }: any) { return <button className="recipe-search-row" onClick={open}><img src={foodImg(recipe.imageSeed)} alt=""/><div><b>{recipe.name}</b><small>{recipe.prepMinutes + recipe.cookMinutes} min · {money(recipe.costDollars)} · {recipe.servings} servings</small></div><span onClick={e => {e.stopPropagation();toggle();}}><Heart fill={favorite ? "#ff5a3c" : "none"}/></span></button> }
