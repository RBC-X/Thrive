import { jsonWithEtag, recipes, stableGeneratedAt } from "../../../../lib/thrive-data";

export async function GET(request: Request) {
  const url = new URL(request.url);
  let out = [...recipes];
  const section = url.searchParams.get("section")?.trim().slice(0, 50);
  const query = url.searchParams.get("query")?.trim().slice(0, 100).toLowerCase();
  if (section) out = out.filter((recipe) => recipe.section === section);
  if (query) {
    out = out.filter(
      (recipe) => recipe.name.toLowerCase().includes(query) || recipe.tags.some((tag) => tag.toLowerCase().includes(query)) || recipe.ingredients.some((ingredient) => ingredient.name.toLowerCase().includes(query)),
    );
  }
  return jsonWithEtag(request, { recipes: out, generatedAt: stableGeneratedAt() }, "public, max-age=300");
}
