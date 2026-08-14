import coupons from "../app/data/coupons.json";
import recipes from "../app/data/recipes.json";
import catalog from "../app/data/catalog.json";
import dealTemplates from "../app/data/deals.json";

export { coupons, recipes, catalog };

function hashString(value: string) {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) hash = (Math.imul(hash, 31) + value.charCodeAt(i)) | 0;
  return Math.abs(hash);
}

export function dailyDeals(now = new Date()) {
  const start = new Date(now.getFullYear(), 0, 0);
  const day = Math.floor((now.getTime() - start.getTime()) / 86_400_000);
  const activeCount = Math.min(30 + (day % 12), dealTemplates.length);
  return Array.from({ length: activeCount }, (_, index) => {
    const template = dealTemplates[(index + day) % dealTemplates.length];
    const seed = hashString(`${template.id}:${day}`);
    const jitter = 0.92 + (seed % 17) / 100;
    return {
      ...template,
      price: Math.round(template.price * jitter * 100) / 100,
      endsInDays: 1 + (seed % 5),
      unitPrice: template.unitPrice || "",
      size: template.size || null,
    };
  });
}

function etagHash(value: string) {
  let h = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return `\"${(h >>> 0).toString(16)}\"`;
}

export function jsonWithEtag(request: Request, body: unknown, cacheControl = "no-cache") {
  const payload = JSON.stringify(body);
  const etag = etagHash(payload);
  if (request.headers.get("if-none-match") === etag) {
    return new Response(null, { status: 304, headers: { ETag: etag, "Cache-Control": cacheControl } });
  }
  return new Response(payload, {
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": cacheControl,
      ETag: etag,
      "X-Content-Type-Options": "nosniff",
    },
  });
}

export function errorResponse(code: string, message: string, status = 400) {
  return Response.json({ error: { code, message } }, { status, headers: { "X-Content-Type-Options": "nosniff" } });
}

export async function readJson<T>(request: Request, maxBytes = 262_144): Promise<T> {
  const length = Number(request.headers.get("content-length") || 0);
  if (length > maxBytes) throw new Error("PAYLOAD_TOO_LARGE");
  const text = await request.text();
  if (text.length > maxBytes) throw new Error("PAYLOAD_TOO_LARGE");
  return JSON.parse(text) as T;
}
