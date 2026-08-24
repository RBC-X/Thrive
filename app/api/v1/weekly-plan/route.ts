import { errorResponse, isRecord, readJson, recipes } from "../../../../lib/thrive-data";
import { weeklyPlan, type MealFocus, type PantryItem } from "../../../../lib/thrive-engine";

export async function POST(request: Request) {
  try {
    const body = await readJson<unknown>(request);
    if (!isRecord(body)) return errorResponse("VALIDATION_ERROR", "The weekly plan request must be a JSON object");

    const budget = Number(body.budget);
    const people = Number(body.people ?? 4);
    const nights = Number(body.nights ?? 7);
    if (
      !Array.isArray(body.pantry) ||
      body.pantry.length > 250 ||
      !Number.isFinite(budget) ||
      budget <= 0 ||
      budget > 5000 ||
      !Number.isInteger(people) ||
      people < 1 ||
      people > 20 ||
      !Number.isInteger(nights) ||
      nights < 1 ||
      nights > 7
    ) {
      return errorResponse(
        "VALIDATION_ERROR",
        "Provide up to 250 pantry items, a budget between 1 and 5000, 1 to 20 people, and 1 to 7 nights",
      );
    }

    const pantry = body.pantry
      .map((item): PantryItem | null => {
        if (!isRecord(item)) return null;
        const name = String(item.name || "").trim().slice(0, 100);
        return name ? { ...item, name } as PantryItem : null;
      })
      .filter((item): item is PantryItem => item !== null);
    const focus: MealFocus = body.focus === "use_expiring" || body.focus === "lowest_cost" ? body.focus : "balanced";
    return Response.json({ plan: weeklyPlan(pantry, recipes, budget, people, focus, nights) });
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === "PAYLOAD_TOO_LARGE";
    return errorResponse(tooLarge ? "PAYLOAD_TOO_LARGE" : "INVALID_JSON", "The weekly plan request could not be read", tooLarge ? 413 : 400);
  }
}
