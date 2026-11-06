"use client";
import { useHydrated } from "@/shared/hooks/use-hydrated";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { authApi } from "./api";
import { useCountdown } from "@/shared/hooks/use-countdown";
import { ApiError } from "@/shared/api/error";
import { Button } from "@/shared/ui/button";
import { Notice } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function VerificationWaiting({
  initialEmail = "",
  onChangeEmail,
}: {
  initialEmail?: string;
  onChangeEmail?: () => void;
}) {
  const hydrated = useHydrated();
  const mutation = useMutation({ mutationFn: authApi.resend });
  const seconds = useCountdown(
    mutation.error instanceof ApiError ? mutation.error.retryAt : undefined,
  );
  return (
    <div className="flex flex-col gap-5">
      <Notice>
        Письмо с подтверждением может прийти через несколько минут.
      </Notice>
      <p className="text-muted-foreground">
        Письма нет? Проверьте папку «Спам» или запросите его ещё раз.
      </p>
      {initialEmail ? (
        <div className="space-y-5">
          <div className="space-y-1.5">
            <p className="font-medium">Email</p>
            <p className="break-all text-muted-foreground">{initialEmail}</p>
          </div>
          <ApiErrorNotice error={mutation.error} />
          {mutation.isSuccess && (
            <Notice tone="success">
              Запрос принят. Проверьте входящие и папку «Спам». Письмо может
              прийти через несколько минут.
            </Notice>
          )}
          <Button
            onClick={() => mutation.mutate({ email: initialEmail })}
            variant="outline"
            className="w-full"
            disabled={!hydrated || mutation.isPending || seconds > 0}
          >
            {mutation.isPending
              ? "Отправляем…"
              : seconds > 0
                ? `Повторить через ${seconds} сек.`
                : "Отправить письмо повторно"}
          </Button>
        </div>
      ) : (
        <Notice>
          Войдите с почтой и паролем, чтобы запросить новое письмо
          подтверждения.
        </Notice>
      )}
      {onChangeEmail ? (
        <Button variant="ghost" onClick={onChangeEmail}>
          Изменить email
        </Button>
      ) : (
        <Button asChild variant="ghost">
          <Link href="/register">Изменить email</Link>
        </Button>
      )}
      <Button asChild variant="outline">
        <Link href="/login">Перейти ко входу</Link>
      </Button>
    </div>
  );
}
