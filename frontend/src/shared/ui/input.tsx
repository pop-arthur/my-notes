import type { ComponentProps } from "react";
import { cn } from "@/shared/lib/cn";
export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      data-slot="input"
      className={cn(
        "min-h-12 w-full min-w-0 rounded-md border bg-card px-3 py-2.5 text-base placeholder:text-muted-foreground hover:border-muted-foreground focus-visible:border-primary disabled:bg-muted aria-invalid:border-destructive",
        className,
      )}
      {...props}
    />
  );
}
export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "min-h-20 w-full resize-y rounded-md border bg-card px-3 py-2.5 text-base focus-visible:border-primary disabled:bg-muted aria-invalid:border-destructive",
        className,
      )}
      {...props}
    />
  );
}
