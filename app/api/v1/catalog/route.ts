import { catalog, jsonWithEtag, stableGeneratedAt } from "../../../../lib/thrive-data";

export async function GET(request: Request) {
  const query = new URL(request.url).searchParams.get("query")?.trim().slice(0, 100).toLowerCase();
  const out = query ? catalog.filter((item) => item.name.toLowerCase().includes(query)) : catalog;
  return jsonWithEtag(request, { catalog: out.slice(0, 100), generatedAt: stableGeneratedAt() }, "public, max-age=300");
}
