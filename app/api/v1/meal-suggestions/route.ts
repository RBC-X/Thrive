import { errorResponse, isRecord, readJson, recipes } from "../../../../lib/thrive-data";
import { suggestMeals, type MealFocus, type PantryItem } from "../../../../lib/thrive-engine";

export async function POST(request: Request) {
  try {
    const body = await readJson<unknown>(request);
    if (!isRecord(body)) return errorResponse("VALIDATION_ERROR", "The meal request must be a JSON object");

    const limit = Number(body.limit ?? 3);
    if (!Array.isArray(body.pantry) || body.pantry.length > 250 || !Number.isInteger(limit) || limit < 1 || limit > 10) {
      return errorResponse("VALIDATION_ERROR", "Provide up to 250 pantry items and a limit between 1 and 10");
    }

    const pantry = body.pantry
      .map((item): PantryItem | null => {
        if (!isRecord(item)) return null;
        const name = String(item.name || "").trim().slice(0, 100);
        return name ? { ...item, name } as PantryItem : null;
      })
      .filter((item): item is PantryItem => item !== null);
    const focus: MealFocus = body.focus === "use_expiring" || body.focus === "lowest_cost" ? body.focus : "balanced";
    return Response.json({ suggestions: suggestMeals(pantry, recipes, focus, limit), generatedAt: new Date().toISOString() });
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === "PAYLOAD_TOO_LARGE";
    return errorResponse(tooLarge ? "PAYLOAD_TOO_LARGE" : "INVALID_JSON", "The meal request could not be read", tooLarge ? 413 : 400);
  }
}
