"use client";
import { ApiError, errorMessage } from "@/shared/api/error";
import { useCountdown } from "@/shared/hooks/use-countdown";
import { Notice } from "./notice";
export function ApiErrorNotice({ error }: { error: unknown }) {
  const remaining = useCountdown(
    error instanceof ApiError ? error.retryAt : undefined,
  );
  if (!error) return null;
  return (
    <Notice tone="error">
      {errorMessage(error)}
      {remaining > 0 && (
        <p className="mt-1">Повторить через {remaining} сек.</p>
      )}
    </Notice>
  );
}
