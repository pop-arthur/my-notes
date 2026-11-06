import { getCloudflareContext } from "@opennextjs/cloudflare";
import { handleOAuthNavigation } from "@/features/auth/server/oauth-navigation";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export function GET(request: Request) {
  let edge: CloudflareEnv | undefined;
  try {
    edge = getCloudflareContext().env;
  } catch {
    /* Regular Next dev. */
  }
  return handleOAuthNavigation(request, "start", edge);
}
