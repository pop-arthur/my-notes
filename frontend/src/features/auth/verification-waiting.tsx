"use client";
import { useHydrated } from "@/shared/hooks/use-hydrated";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { authApi } from "./api";
import { emailFormSchema } from "@/shared/lib/validation";
import { useCountdown } from "@/shared/hooks/use-countdown";
import { ApiError } from "@/shared/api/error";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { Notice } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function VerificationWaiting({
  initialEmail = "",
}: {
  initialEmail?: string;
}) {
  const hydrated = useHydrated();
  const form = useForm<z.infer<typeof emailFormSchema>>({
    resolver: zodResolver(emailFormSchema),
    defaultValues: { email: initialEmail },
  });
  const mutation = useMutation({ mutationFn: authApi.resend });
  const seconds = useCountdown(
    mutation.error instanceof ApiError ? mutation.error.retryAt : undefined,
  );
  return (
    <div className="flex flex-col gap-5">
      <Notice>
        Если регистрация доступна для этого адреса, мы отправим письмо с
        подтверждением.
      </Notice>
      <p className="text-muted-foreground">
        Письма нет? Проверьте папку «Спам» или запросите его ещё раз.
      </p>
      <form
        noValidate
        onSubmit={form.handleSubmit((values) => mutation.mutate(values))}
        className="space-y-5"
      >
        <Field
          id="resend-email"
          label="Email"
          error={form.formState.errors.email?.message}
        >
          <Input
            {...form.register("email")}
            {...fieldAccessibility(
              "resend-email",
              form.formState.errors.email?.message,
            )}
            type="email"
            autoComplete="email"
            placeholder="you@example.ru"
            disabled={
              !hydrated || !form.formState.isReady || mutation.isPending
            }
          />
        </Field>
        <ApiErrorNotice error={mutation.error} />
        {mutation.isSuccess && (
          <Notice tone="success">
            Если действие доступно для этого адреса, письмо будет отправлено.
          </Notice>
        )}
        <Button
          type="submit"
          variant="outline"
          className="w-full"
          disabled={
            !hydrated ||
            !form.formState.isReady ||
            mutation.isPending ||
            seconds > 0
          }
        >
          {mutation.isPending
            ? "Отправляем…"
            : seconds > 0
              ? `Повторить через ${seconds} сек.`
              : "Отправить письмо повторно"}
        </Button>
      </form>
      <Button asChild variant="outline">
        <Link href="/login">Перейти ко входу</Link>
      </Button>
    </div>
  );
}
