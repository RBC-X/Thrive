import { dailyDeals, errorResponse, jsonWithEtag, stableGeneratedAt } from "../../../../lib/thrive-data";

export async function GET(request: Request) {
  const url = new URL(request.url);
  let out = dailyDeals();
  const category = url.searchParams.get("category")?.trim().slice(0, 50);
  if (category) out = out.filter((deal) => deal.category.toLowerCase() === category.toLowerCase());
  const rawLimit = url.searchParams.get("limit");
  const limit = rawLimit === null ? 100 : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 0 || limit > 100) return errorResponse("VALIDATION_ERROR", "limit must be an integer between 0 and 100");
  return jsonWithEtag(request, { deals: out.slice(0, limit), generatedAt: stableGeneratedAt() }, "public, max-age=300");
}
