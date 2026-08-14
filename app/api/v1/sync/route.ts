import { catalog, coupons, dailyDeals, jsonWithEtag, recipes } from "../../../../lib/thrive-data";
export async function GET(request: Request) {
  const body={version:4,generatedAt:new Date().toISOString(),source:["daily-rotation","bundled-feed"],deals:dailyDeals(),coupons,recipes,catalog,update:null};
  return jsonWithEtag(request,body,"public, max-age=300, stale-while-revalidate=3600");
}
