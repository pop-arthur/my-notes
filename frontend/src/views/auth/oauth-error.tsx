"use client";
import { useSearchParams } from "next/navigation";
import { ApiError } from "@/shared/api/error";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
import { Notice } from "@/shared/ui/notice";
export function OAuthError() {
  const params = useSearchParams();
  const code = params.get("error");
  if (code === "google_unavailable")
    return (
      <Notice tone="error">
        Вход через Google сейчас недоступен. Вы можете войти по почте.
      </Notice>
    );
  if (code === "oauth_unavailable")
    return (
      <Notice tone="error">
        Вход через Google временно недоступен. Попробуйте позже или войдите по
        почте.
      </Notice>
    );
  if (code === "rate_limited") {
    const value = Number(params.get("retryAt"));
    const retryAt =
      Number.isSafeInteger(value) && value > 0 ? value : undefined;
    return (
      <ApiErrorNotice
        key={retryAt}
        error={new ApiError(429, "rate_limited", [], retryAt)}
      />
    );
  }
  if (code === "oauth_failed")
    return (
      <Notice tone="error">
        Не удалось войти через Google. Попробуйте ещё раз или войдите по почте.
      </Notice>
    );
  if (params.get("reason") === "session-expired")
    return <Notice>Сессия завершена. Войдите снова.</Notice>;
  return null;
}
