"use client";
import { Suspense, useState } from "react";
import { AuthHeading } from "@/widgets/auth-shell/auth-shell";
import { CredentialsForm } from "@/features/auth/credentials-form";
import { GoogleButton } from "@/features/auth/google-button";
import { VerificationWaiting } from "@/features/auth/verification-waiting";
import { OAuthError } from "./oauth-error";
export function CredentialsPage({ mode }: { mode: "login" | "register" }) {
  const [pendingEmail, setPendingEmail] = useState<string>();
  if (pendingEmail)
    return (
      <>
        <AuthHeading
          title="Проверьте почту"
          description="Откройте письмо и перейдите по ссылке, чтобы подтвердить адрес."
        />
        <VerificationWaiting initialEmail={pendingEmail} />
      </>
    );
  const register = mode === "register";
  return (
    <>
      <AuthHeading
        title={register ? "Создать аккаунт" : "Вход"}
        description={
          register
            ? "Начните с одной задачи. Остальное — по порядку."
            : "Ваши задачи — в одном месте."
        }
      />
      <Suspense>
        <OAuthError />
      </Suspense>
      <GoogleButton register={register} />
      <CredentialsForm mode={mode} onRegistered={setPendingEmail} />
    </>
  );
}
