import type { ReactNode } from "react";
import { LoaderCircle } from "lucide-react";
import { cn } from "@/shared/lib/cn";
export function Notice({
  children,
  tone = "info",
  className,
}: {
  children: ReactNode;
  tone?: "info" | "error" | "success";
  className?: string;
}) {
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className={cn(
        "flex items-start gap-2 rounded-md p-4 text-sm",
        tone === "error"
          ? "bg-error-surface text-destructive"
          : tone === "success"
            ? "bg-success-surface text-success"
            : "bg-info-surface",
        className,
      )}
    >
      <div className="min-w-0">{children}</div>
    </div>
  );
}
export function Loading({ label = "Загрузка…" }: { label?: string }) {
  return (
    <div
      role="status"
      className="flex min-h-24 items-center justify-center gap-2 text-muted-foreground"
    >
      <LoaderCircle size={20} className="animate-spin" aria-hidden />
      {label}
    </div>
  );
}
