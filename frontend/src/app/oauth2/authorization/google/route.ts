import { handleOAuthNavigation } from "@/features/auth/server/oauth-navigation";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export function GET(request: Request) {
  return handleOAuthNavigation(request, "start");
}
