import next from "../.open-next/worker.js";
import { proxyApi } from "../src/shared/api/backend-transport";
const worker = {
  async fetch(request: Request, env: CloudflareEnv, context: unknown) {
    const path = new URL(request.url).pathname;
    if (
      path === "/api" ||
      path.startsWith("/api/") ||
      path === "/actuator/health"
    )
      return proxyApi(request, env);
    const response = await next.fetch(request, env, context);
    const result = new Response(response.body, response);
    result.headers.set("Cache-Control", "no-store");
    return result;
  },
};
export default worker;
