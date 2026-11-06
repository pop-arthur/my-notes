"use client";
import type { AriaAttributes, ReactNode } from "react";
import * as Label from "@radix-ui/react-label";
export function Field({
  id,
  label,
  hint,
  error,
  children,
}: {
  id: string;
  label: string;
  hint?: ReactNode;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label.Root htmlFor={id} className="font-medium">
        {label}
      </Label.Root>
      {children}
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
export function fieldAccessibility(
  id: string,
  error?: string,
  hint = false,
): AriaAttributes & { id: string } {
  return {
    id,
    "aria-invalid": !!error,
    "aria-describedby":
      [hint && `${id}-hint`, error && `${id}-error`]
        .filter(Boolean)
        .join(" ") || undefined,
  };
}
