"use client";
import { useHydrated } from "@/shared/hooks/use-hydrated";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { sessionKey } from "@/entities/session/api";
import { taskKeys } from "@/entities/task/api";
import { ApiError } from "@/shared/api/error";
import { applyFieldErrors } from "@/shared/lib/form-errors";
import { loginSchema, registerSchema } from "@/shared/lib/validation";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { PasswordInput } from "@/shared/ui/password-input";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
import { authApi } from "./api";
import { useCountdown } from "@/shared/hooks/use-countdown";

type Values = z.infer<typeof loginSchema>;
export function CredentialsForm({
  mode,
  onRegistered,
}: {
  mode: "login" | "register";
  onRegistered?: (email: string) => void;
}) {
  const hydrated = useHydrated();
  const router = useRouter();
  const client = useQueryClient();
  const registration = mode === "register";
  const form = useForm<Values>({
    resolver: zodResolver(registration ? registerSchema : loginSchema),
    defaultValues: { email: "", password: "" },
  });
  const mutation = useMutation({
    mutationFn: async (values: Values) => {
      if (registration) {
        await authApi.register(values);
        return null;
      }
      return authApi.login(values);
    },
    onSuccess: async (user, values) => {
      form.reset();
      if (registration) onRegistered?.(values.email);
      else {
        await client.cancelQueries({ queryKey: taskKeys.all });
        client.removeQueries({ queryKey: taskKeys.all });
        client.setQueryData(sessionKey, user);
        router.replace("/");
      }
    },
    onError: (error) =>
      applyFieldErrors(error, form.setError, ["email", "password"]),
  });
  const seconds = useCountdown(
    mutation.error instanceof ApiError ? mutation.error.retryAt : undefined,
  );
  return (
    <>
      <form
        noValidate
        className="flex flex-col gap-5"
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
        <Field
          id="password"
          label="Пароль"
          error={form.formState.errors.password?.message}
          hint={registration ? "От 12 до 128 символов" : undefined}
        >
          <PasswordInput
            {...form.register("password")}
            {...fieldAccessibility(
              "password",
              form.formState.errors.password?.message,
              registration,
            )}
            autoComplete={registration ? "new-password" : "current-password"}
            placeholder={registration ? "Придумайте пароль" : "Введите пароль"}
            disabled={
              !hydrated || !form.formState.isReady || mutation.isPending
            }
          />
        </Field>
        <ApiErrorNotice error={mutation.error} />
        {mutation.error instanceof ApiError &&
          mutation.error.code === "email_not_verified" && (
            <Button
              variant="outline"
              onClick={() => onRegistered?.(form.getValues("email"))}
            >
              Подтвердить почту
            </Button>
          )}
        <Button
          type="submit"
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
              : registration
                ? "Создать аккаунт"
                : "Войти"}
        </Button>
      </form>
      {!registration && (
        <Button asChild variant="ghost" className="self-center">
          <Link href="/forgot-password">Забыли пароль?</Link>
        </Button>
      )}
      <div className="flex flex-col items-center">
        <p className="text-muted-foreground">
          {registration ? "Уже есть аккаунт?" : "Ещё нет аккаунта?"}
        </p>
        <Button asChild variant="ghost">
          <Link href={registration ? "/login" : "/register"}>
            {registration ? "Войти" : "Создать аккаунт"}
          </Link>
        </Button>
      </div>
    </>
  );
}
