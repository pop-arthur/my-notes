"use client";

import { useEffect } from "react";
import { X } from "lucide-react";
import { Button } from "./button";

const TOAST_DURATION_MS = 1_000;

export function SuccessToast({
  message,
  onDismiss,
}: {
  message: string;
  onDismiss: () => void;
}) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, TOAST_DURATION_MS);
    return () => clearTimeout(timer);
  }, [onDismiss]);

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed right-5 bottom-5 left-5 z-40 flex items-center gap-3 rounded-lg border bg-card py-1 pr-1 pl-4 shadow-sm sm:left-auto"
    >
      <span className="min-w-0 flex-1">{message}</span>
      <Button
        variant="ghost"
        className="size-11 p-0 text-muted-foreground"
        aria-label="Закрыть уведомление"
        onClick={onDismiss}
      >
        <X size={18} aria-hidden />
      </Button>
    </div>
  );
}
