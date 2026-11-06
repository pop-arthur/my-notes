"use client";
import { useHydrated } from "@/shared/hooks/use-hydrated";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { authApi } from "./api";
import { resetSchema } from "@/shared/lib/validation";
import { applyFieldErrors } from "@/shared/lib/form-errors";
import { sessionKey } from "@/entities/session/api";
import { taskKeys } from "@/entities/task/api";
import { Button } from "@/shared/ui/button";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { PasswordInput } from "@/shared/ui/password-input";
import { Notice } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
function MissingToken({ reset = false }: { reset?: boolean }) {
  return (
    <>
      <Notice tone="error">
        Ссылка недействительна или срок её действия истёк. Запросите новую.
      </Notice>
      <Button asChild variant="outline">
        <Link href={reset ? "/forgot-password" : "/check-email"}>
          Запросить новую ссылку
        </Link>
      </Button>
    </>
  );
}
export function VerifyEmailAction() {
  const hydrated = useHydrated();
  const token = useSearchParams().get("token");
  const router = useRouter();
  const mutation = useMutation({
    mutationFn: authApi.verify,
    onSuccess: () => router.replace("/email-verified"),
  });
  if (!token || token.length > 512) return <MissingToken />;
  return (
    <>
      <p className="text-muted-foreground">
        Нажмите кнопку, чтобы завершить подтверждение адреса.
      </p>
      <ApiErrorNotice error={mutation.error} />
      <Button
        disabled={!hydrated || mutation.isPending}
        onClick={() => mutation.mutate({ token })}
      >
        {mutation.isPending ? "Подтверждаем…" : "Подтвердить почту"}
      </Button>
      {mutation.isError && (
        <Button asChild variant="outline">
          <Link href="/check-email">Запросить новую ссылку</Link>
        </Button>
      )}
    </>
  );
}
export function ResetPasswordForm() {
  const hydrated = useHydrated();
  const token = useSearchParams().get("token");
  const router = useRouter();
  const client = useQueryClient();
  const form = useForm<z.infer<typeof resetSchema>>({
    resolver: zodResolver(resetSchema),
    defaultValues: { newPassword: "" },
  });
  const mutation = useMutation({
    mutationFn: authApi.reset,
    onSuccess: async () => {
      form.reset();
      await client.cancelQueries({ queryKey: taskKeys.all });
      client.removeQueries({ queryKey: taskKeys.all });
      await client.invalidateQueries({ queryKey: sessionKey });
      router.replace("/password-updated");
    },
    onError: (error) => applyFieldErrors(error, form.setError, ["newPassword"]),
  });
  if (!token || token.length > 512) return <MissingToken reset />;
  return (
    <>
      <form
        noValidate
        className="space-y-5"
        onSubmit={form.handleSubmit((values) =>
          mutation.mutate({ token, ...values }),
        )}
      >
        <Field
          id="new-password"
          label="Новый пароль"
          hint="От 12 до 128 символов"
          error={form.formState.errors.newPassword?.message}
        >
          <PasswordInput
            {...form.register("newPassword")}
            {...fieldAccessibility(
              "new-password",
              form.formState.errors.newPassword?.message,
              true,
            )}
            autoComplete="new-password"
            placeholder="Введите новый пароль"
            disabled={
              !hydrated || !form.formState.isReady || mutation.isPending
            }
          />
        </Field>
        <ApiErrorNotice error={mutation.error} />
        <Button
          type="submit"
          className="w-full"
          disabled={!hydrated || !form.formState.isReady || mutation.isPending}
        >
          {mutation.isPending ? "Сохраняем…" : "Сохранить пароль"}
        </Button>
      </form>
      {mutation.isError && (
        <Button asChild variant="outline">
          <Link href="/forgot-password">Запросить новую ссылку</Link>
        </Button>
      )}
      <Button asChild variant="ghost" className="self-center">
        <Link href="/login">Вернуться ко входу</Link>
      </Button>
    </>
  );
}
