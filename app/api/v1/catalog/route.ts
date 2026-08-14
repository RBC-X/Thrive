import { catalog, jsonWithEtag } from "../../../../lib/thrive-data";
export async function GET(request: Request) { const query=new URL(request.url).searchParams.get("query")?.toLowerCase(); const out=query?catalog.filter(x=>x.name.toLowerCase().includes(query)):catalog; return jsonWithEtag(request,{catalog:out.slice(0,100),generatedAt:new Date().toISOString()},"public, max-age=300"); }
