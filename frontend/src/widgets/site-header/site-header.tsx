import type { ReactNode } from "react";
import { Brand } from "@/shared/ui/brand";
export function SiteHeader({
  account,
  email,
}: {
  account?: ReactNode;
  email?: string;
}) {
  return (
    <header className="border-b bg-card px-5 py-4 sm:px-12 sm:py-[18px]">
      <div className="flex min-h-7 items-center justify-between gap-3">
        <Brand />
        {account && (
          <div className="flex min-w-0 items-center gap-5">
            {email && (
              <span className="hidden max-w-80 truncate text-muted-foreground sm:block">
                {email}
              </span>
            )}
            {account}
          </div>
        )}
      </div>
      {email && (
        <p className="mt-3 break-all text-xs text-muted-foreground sm:hidden">
          {email}
        </p>
      )}
    </header>
  );
}
