import type recipesData from "../app/data/recipes.json";
import type dealsData from "../app/data/deals.json";

export type Recipe = (typeof recipesData)[number];
export type Deal = Omit<(typeof dealsData)[number], "size" | "unitPrice"> & { size?: string | null; unitPrice?: string };
export type PantryItem = { id?: string; name: string; category?: string; location?: string; quantity?: number; expiresAt?: number | null };
export type ShoppingItem = { id: string; name: string; category: string; quantity: number; unit?: string; price?: number; estPrice?: number; checked?: boolean };
export type MealFocus = "balanced" | "use_expiring" | "lowest_cost";

const staples = new Set(["salt","pepper","black pepper","cooking oil","olive oil","water","sugar","flour","garlic","onion","butter"]);
const stopwords = new Set(["fresh","boneless","skinless","uncooked","cooked","plain","whole","organic","large","small","medium","raw","low-fat","low","fat","2","store-bought","store","bought","leftover","canned","diced","shredded","grated","chopped","minced","sliced","ground","extra","virgin","big","smaller","flavored"]);
const prices: Record<string, number> = {
  "chicken breast":4.5,"chicken thighs":4,"ground beef":5,"ground turkey":4.5,"pork chops":4,"bacon":4,"eggs":2.5,"milk":3,"cheddar cheese":3.5,"mozzarella":3,"parmesan":3.5,"cream":2.5,"sour cream":2,"yogurt":2.5,"rice":2.5,"pasta":1.5,"spaghetti":1.5,"penne":1.5,"bread":2.5,"tortillas":2.5,"flour tortillas":2.5,"potatoes":3,"sweet potatoes":2.5,"carrots":1.5,"celery":1.5,"broccoli":2,"spinach":2.5,"lettuce":2,"tomatoes":2.5,"bell pepper":1.5,"zucchini":1.5,"mushrooms":2,"canned tomatoes":1.5,"tomato sauce":1.5,"tomato paste":1,"black beans":1.5,"kidney beans":1.5,"chickpeas":1.5,"corn":1.5,"peas":1.5,"green beans":2,"salsa":2.5,"soy sauce":2,"hot sauce":2.5,"mustard":2,"ketchup":2,"mayonnaise":3,"pasta sauce":2.5,"lemon":.8,"lime":.8,"bananas":1.5,"apples":3,"frozen vegetables":2,"frozen peas":2,"canned tuna":1.5,"sausage":3.5,"broth":2,"chicken broth":2,"taco seasoning":1.5,"peanut butter":3,"honey":3.5,"baking powder":1.5,"panko":2.5,"breadcrumbs":2,"bbq sauce":2.5,"worcestershire":3,"coconut milk":2.5,"curry paste":3,"ginger":1.5,"cilantro":1,"parsley":1,"paprika":2,"cumin":2,"chili powder":2,"italian seasoning":2,"green onions":1,"avocado":1.5,"white rice":2.5,"brown rice":3,"lentils":2,"tuna":1.5,
};

const tokens = (value: string) => new Set(value.toLowerCase().replace(/[^a-z0-9 ]/g," ").split(/\s+/).filter(x => x.length > 1 && !stopwords.has(x)));
const intersects = (a: string, b: string) => {
  const left = tokens(a), right = tokens(b);
  if (!left.size || !right.size) return false;
  return [...left].some(x => right.has(x)) || [...right].some(x => left.has(x));
};
export const isStaple = (name: string) => staples.has(name.toLowerCase().trim()) || [...tokens(name)].some(x => staples.has(x));
export const estimateIngredientPrice = (name: string) => prices[name.toLowerCase().trim()] ?? Object.entries(prices).find(([key]) => name.toLowerCase().includes(key))?.[1] ?? 2;

export function scoreRecipe(recipe: Recipe, pantry: PantryItem[], focus: MealFocus = "balanced") {
  const now = Date.now();
  const expiring = pantry.filter(item => item.expiresAt && item.expiresAt - now < 259_200_000).map(item => item.name);
  let used = 0, required = 0, expiringUsed = 0;
  const usedItems = new Set<string>();
  const missingItems: { name: string; estCost: number }[] = [];
  for (const ingredient of recipe.ingredients) {
    if (isStaple(ingredient.name)) continue;
    required += 1;
    const match = pantry.find(item => intersects(item.name, ingredient.name));
    if (match) {
      used += 1; usedItems.add(match.name);
      if (expiring.includes(match.name)) expiringUsed += 1;
    } else if (!("optional" in ingredient && ingredient.optional)) missingItems.push({ name: ingredient.name, estCost: estimateIngredientPrice(ingredient.name) });
  }
  if (!required || !used) return null;
  const coverage = used / required;
  const extraCost = missingItems.reduce((sum, item) => sum + item.estCost, 0);
  const coverageScore = coverage * 3 + expiringUsed * (focus === "use_expiring" ? 1.6 : .8) - missingItems.length * .45 - Math.min(extraCost,14) * (focus === "lowest_cost" ? .2 : .05);
  return { recipe, usedItems:[...usedItems], expiringItemsUsed:expiring.filter(x => usedItems.has(x)), missingItems, coverageScore, estimatedExtraCost:extraCost, usesCount:usedItems.size, isZeroShopping:!missingItems.length, aiTip: missingItems.length ? `You already have ${usedItems.size} ingredient${usedItems.size === 1 ? "" : "s"}. Pick up ${missingItems.slice(0,2).map(x=>x.name).join(" and ")} to finish it.` : "You have everything you need. This is a true pantry win." };
}

export type ScoredRecipe = NonNullable<ReturnType<typeof scoreRecipe>>;

const isScoredRecipe = (value: ScoredRecipe | null): value is ScoredRecipe => value !== null;

export function suggestMeals(pantry: PantryItem[], recipes: Recipe[], focus: MealFocus = "balanced", limit = 3) {
  const safeLimit = Number.isInteger(limit) ? Math.max(1, Math.min(10, limit)) : 3;
  return recipes.map(recipe => scoreRecipe(recipe, pantry, focus)).filter(isScoredRecipe).sort((a,b) => b.coverageScore-a.coverageScore).slice(0,safeLimit);
}

export function weeklyPlan(pantry: PantryItem[], recipes: Recipe[], budget: number, people = 4, focus: MealFocus = "balanced", nights = 7) {
  const days = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];
  const count = Number.isInteger(nights) ? Math.max(1,Math.min(7,nights)) : 7;
  const perNight = budget / count;
  const used = new Set<string>();
  const planned: { day: string; suggestion: ScoredRecipe }[] = [];
  for (let i=0;i<count;i+=1) {
    const candidates = recipes.filter(r => !used.has(r.id)).map(recipe => {
      const scored = scoreRecipe(recipe,pantry,focus);
      const missingItems = scored?.missingItems ?? recipe.ingredients.filter(x => !isStaple(x.name) && !("optional" in x && x.optional)).map(x => ({name:x.name,estCost:estimateIngredientPrice(x.name)}));
      const suggestion = scored ?? { recipe, usedItems:[], expiringItemsUsed:[], missingItems, coverageScore:-1, estimatedExtraCost:missingItems.reduce((s,x)=>s+x.estCost,0), usesCount:0, isZeroShopping:false, aiTip:"A low-cost choice that keeps the week balanced." };
      return { suggestion, score:suggestion.coverageScore, cost:recipe.costDollars };
    });
    const fits = candidates.filter(x => x.cost <= perNight*1.2);
    const pick = fits.length ? fits.sort((a,b)=>b.score-a.score)[0] : candidates.sort((a,b)=>a.cost-b.cost)[0];
    if (!pick) break;
    planned.push({day:days[i],suggestion:pick.suggestion}); used.add(pick.suggestion.recipe.id);
  }
  const combined = new Map<string,{name:string;estCost:number}>();
  for (const night of planned) for (const item of night.suggestion.missingItems) combined.set(item.name,{name:item.name,estCost:(combined.get(item.name)?.estCost||0)+item.estCost});
  const recipeCost = planned.reduce((s,n)=>s+n.suggestion.recipe.costDollars,0);
  const extraCost = [...combined.values()].reduce((s,x)=>s+x.estCost,0);
  const totalCost = recipeCost + extraCost;
  return { nights:planned,budget,people,recipeCost,extraCost,totalCost,combinedShopping:[...combined.values()],underBudget:totalCost<=budget,remaining:Math.max(0,budget-totalCost),overshoot:Math.max(0,totalCost-budget),nightsCount:planned.length,aiTip:totalCost<=budget?`This plan leaves about $${Math.max(0,budget-totalCost).toFixed(2)} in your budget.`:`Swap one higher-cost dinner for a pantry-heavy meal to close the $${Math.max(0,totalCost-budget).toFixed(2)} gap.` };
}

const families: Record<string,number> = {lb:0,oz:0,kg:0,g:0,gal:1,qt:1,pt:1,floz:1,ct:2,pack:2,dozen:2,each:2,ea:2,bottle:2,jar:2,can:2,bag:2,box:2,tub:2};
const toBase: Record<string,number> = {lb:1,oz:1/16,kg:2.20462,g:2.20462/1000,gal:1,qt:.25,pt:.125,floz:1/128,ct:1,pack:1,dozen:12,each:1,ea:1,bottle:1,jar:1,can:1,bag:1,box:1,tub:1};
function parseUnit(value?: string | null, bare=false) { if (!value) return null; const match = bare ? ["", "1", value.trim().toLowerCase()] : value.trim().toLowerCase().match(/([0-9.]+)\s*([a-z]+)/); if (!match) return null; const qty=Number(match[1]),unit=match[2]; return qty>0 && unit in families ? {qty,unit,family:families[unit],baseQty:qty*toBase[unit]} : null; }
function matchScore(item: ShoppingItem, deal: Deal) { const it=tokens(item.name),dt=tokens(deal.productName); const overlap=[...it].filter(x=>dt.has(x)).length; const category=deal.category.toLowerCase()===item.category.toLowerCase()?1:0; const keyword=deal.keywords.some(k=>[...tokens(k)].every(x=>it.has(x)))?2:0; return overlap*2+category+keyword; }

export function tripPlan(items: ShoppingItem[], deals: Deal[], budget: number, people: number) {
  const resolved = items.map(item => {
    const est = item.estPrice ?? item.price ?? 0;
    const itemUnit = parseUnit(item.unit,true);
    const candidates = deals.map(deal => ({deal,score:matchScore(item,deal)})).filter(x=>x.score>=2).map(({deal,score}) => {
      const dealUnit=parseUnit(deal.size); let price=deal.price,unitMatched=false;
      if(itemUnit&&dealUnit&&itemUnit.family===dealUnit.family){const effective=deal.price/dealUnit.baseQty*itemUnit.baseQty;if(effective<est){price=effective;unitMatched=true}else if(deal.price>=est)return null}else if(deal.price>=est)return null;
      return {deal,score,price,unitMatched,savings:(est-price)*item.quantity};
    }).filter((candidate): candidate is NonNullable<typeof candidate> => candidate !== null).sort((a,b)=>(Number(b.unitMatched)-Number(a.unitMatched))||(b.savings-a.savings)||(b.score-a.score));
    const best=candidates[0];
    return best?{item,store:best.deal.store,price:best.price,dealFound:true,savings:Math.max(0,best.savings),dealId:best.deal.id,unitMatched:best.unitMatched,unitLabel:best.unitMatched?(best.deal.unitPrice||null):null}:{item,store:"Any store",price:est,dealFound:false,savings:0,dealId:null,unitMatched:false,unitLabel:null};
  });
  const totalBefore=resolved.reduce((s,r)=>s+(r.item.estPrice??r.item.price??0)*r.item.quantity,0),totalAfter=resolved.reduce((s,r)=>s+r.price*r.item.quantity,0),totalSavings=Math.max(0,totalBefore-totalAfter);
  const totals=new Map<string,number>(); for(const r of resolved)totals.set(r.store,(totals.get(r.store)||0)+r.price*r.item.quantity);
  const isOver=totalAfter>budget;
  const brands=["kellogg","post","general mills","heinz","kraft","coca","pepsi","frito","lays","oreo","progresso","campbell","jif","smucker"];
  const swaps=resolved.filter(r=>brands.some(b=>r.item.name.toLowerCase().includes(b))).slice(0,isOver?3:2).map(r=>({itemName:r.item.name,suggestion:"Grab the store-brand version instead — same use, lower price.",saves:r.price*r.item.quantity*.3}));
  return {items:resolved,budget,people,totalBefore,totalAfter,totalSavings,storesUsed:[...totals.entries()].sort((a,b)=>b[1]-a[1]),status:isOver?"OVER_BUDGET":"UNDER_BUDGET",overshoot:Math.max(0,totalAfter-budget),remaining:budget-totalAfter,swaps,perPersonCost:people>0?totalAfter/people:totalAfter,aiInsights:isOver?`You're $${Math.max(0,totalAfter-budget).toFixed(2)} over. Start with store-brand swaps and any nonessential item.`:`You have $${Math.max(0,budget-totalAfter).toFixed(2)} left after the best available matches.`};
}
