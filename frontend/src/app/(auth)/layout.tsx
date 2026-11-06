import type { ReactNode } from "react";
import { SiteHeader } from "@/widgets/site-header/site-header";
import { AuthShell } from "@/widgets/auth-shell/auth-shell";
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <SiteHeader />
      <AuthShell>{children}</AuthShell>
    </>
  );
}
