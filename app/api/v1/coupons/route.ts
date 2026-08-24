import { coupons, errorResponse, jsonWithEtag, stableGeneratedAt } from "../../../../lib/thrive-data";

export async function GET(request: Request) {
  const url = new URL(request.url);
  let out = [...coupons];
  const category = url.searchParams.get("category")?.trim().slice(0, 50);
  if (category) out = out.filter((coupon) => coupon.category.toLowerCase() === category.toLowerCase());
  const rawLimit = url.searchParams.get("limit");
  const limit = rawLimit === null ? 100 : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 0 || limit > 100) return errorResponse("VALIDATION_ERROR", "limit must be an integer between 0 and 100");
  return jsonWithEtag(request, { coupons: out.slice(0, limit), generatedAt: stableGeneratedAt() }, "public, max-age=300");
}
