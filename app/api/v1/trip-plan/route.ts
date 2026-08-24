import { dailyDeals, errorResponse, isRecord, readJson } from "../../../../lib/thrive-data";
import { tripPlan, type ShoppingItem } from "../../../../lib/thrive-engine";

export async function POST(request: Request) {
  try {
    const body = await readJson<unknown>(request);
    if (!isRecord(body)) return errorResponse("VALIDATION_ERROR", "The trip plan request must be a JSON object");

    const budget = Number(body.budget);
    const people = Number(body.people);
    if (
      !Array.isArray(body.items) ||
      body.items.length > 250 ||
      !Number.isFinite(budget) ||
      budget <= 0 ||
      budget > 5000 ||
      !Number.isInteger(people) ||
      people < 1 ||
      people > 20
    ) {
      return errorResponse("VALIDATION_ERROR", "Provide up to 250 items, a budget between 1 and 5000, and 1 to 20 people");
    }

    const items = body.items
      .map((item): ShoppingItem | null => {
        if (!isRecord(item)) return null;
        const name = String(item.name || "").trim().slice(0, 100);
        if (!name) return null;
        const quantity = Number(item.quantity ?? 1);
        const rawPrice = Number(item.price ?? item.estPrice ?? 0);
        return {
          ...item,
          id: String(item.id || crypto.randomUUID()).slice(0, 100),
          name,
          category: String(item.category || "Grocery").trim().slice(0, 50) || "Grocery",
          quantity: Number.isFinite(quantity) ? Math.max(1, Math.min(99, Math.trunc(quantity))) : 1,
          unit: typeof item.unit === "string" ? item.unit.trim().slice(0, 30) : undefined,
          price: Number.isFinite(rawPrice) ? Math.max(0, Math.min(10_000, rawPrice)) : 0,
        } as ShoppingItem;
      })
      .filter((item): item is ShoppingItem => item !== null);
    return Response.json({ plan: tripPlan(items, dailyDeals(), budget, people), generatedAt: new Date().toISOString() });
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === "PAYLOAD_TOO_LARGE";
    return errorResponse(tooLarge ? "PAYLOAD_TOO_LARGE" : "INVALID_JSON", "The trip plan request could not be read", tooLarge ? 413 : 400);
  }
}
