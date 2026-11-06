"use client";
import { useHydrated } from "@/shared/hooks/use-hydrated";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { authApi } from "./api";
import { emailFormSchema } from "@/shared/lib/validation";
import { ApiError } from "@/shared/api/error";
import { useCountdown } from "@/shared/hooks/use-countdown";
import { applyFieldErrors } from "@/shared/lib/form-errors";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { Notice } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function RecoveryForm() {
  const hydrated = useHydrated();
  const form = useForm<z.infer<typeof emailFormSchema>>({
    resolver: zodResolver(emailFormSchema),
    defaultValues: { email: "" },
  });
  const mutation = useMutation({
    mutationFn: authApi.forgot,
    onError: (error) => applyFieldErrors(error, form.setError, ["email"]),
  });
  const seconds = useCountdown(
    mutation.error instanceof ApiError ? mutation.error.retryAt : undefined,
  );
  return (
    <>
      <form
        noValidate
        className="space-y-5"
        onSubmit={form.handleSubmit((values) => mutation.mutate(values))}
      >
        <Field
          id="email"
          label="Email"
          error={form.formState.errors.email?.message}
        >
          <Input
            {...form.register("email")}
            {...fieldAccessibility(
              "email",
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
          <Notice>
            Если действие доступно для этого адреса, письмо будет отправлено.
          </Notice>
        )}
        <Button
          type="submit"
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
              : "Отправить ссылку"}
        </Button>
      </form>
      <Button asChild variant="ghost" className="self-center">
        <Link href="/login">Вернуться ко входу</Link>
      </Button>
    </>
  );
}
