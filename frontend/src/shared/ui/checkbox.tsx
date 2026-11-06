"use client";
import type { ComponentProps } from "react";
import * as CheckboxPrimitive from "@radix-ui/react-checkbox";
import { Check, LoaderCircle } from "lucide-react";
export function Checkbox({
  loading,
  ...props
}: ComponentProps<typeof CheckboxPrimitive.Root> & { loading?: boolean }) {
  return (
    <CheckboxPrimitive.Root
      {...props}
      data-slot="checkbox"
      className="group flex size-11 shrink-0 items-center justify-center rounded-md disabled:cursor-default"
    >
      <span className="flex size-5 items-center justify-center rounded-sm border-[1.5px] border-muted-foreground group-data-[state=checked]:border-primary group-data-[state=checked]:bg-primary group-disabled:bg-muted group-disabled:opacity-60">
        {loading ? (
          <LoaderCircle size={14} className="animate-spin" aria-hidden />
        ) : (
          <CheckboxPrimitive.Indicator>
            <Check size={14} className="text-primary-foreground" aria-hidden />
          </CheckboxPrimitive.Indicator>
        )}
      </span>
    </CheckboxPrimitive.Root>
  );
}
