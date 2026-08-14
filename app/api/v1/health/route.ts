export async function GET() {
  return Response.json({ ok:true, service:"thrive-web", version:4, sources:["daily-rotation","bundled-feed"], time:new Date().toISOString() }, { headers:{ "Cache-Control":"no-store", "X-Content-Type-Options":"nosniff" } });
}
