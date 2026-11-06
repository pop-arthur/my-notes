import { Suspense } from "react";
import Link from "next/link";
import { AuthHeading } from "@/widgets/auth-shell/auth-shell";
import { CredentialsPage } from "./credentials-page";
import { RecoveryForm } from "@/features/auth/recovery-form";
import { VerificationWaiting } from "@/features/auth/verification-waiting";
import {
  ResetPasswordForm,
  VerifyEmailAction,
} from "@/features/auth/email-token-actions";
import { Notice, Loading } from "@/shared/ui/notice";
import { Button } from "@/shared/ui/button";
export function LoginPage() {
  return <CredentialsPage mode="login" />;
}
export function RegisterPage() {
  return <CredentialsPage mode="register" />;
}
export function CheckEmailPage() {
  return (
    <>
      <AuthHeading
        title="Проверьте почту"
        description="Откройте письмо и перейдите по ссылке, чтобы подтвердить адрес."
      />
      <VerificationWaiting />
    </>
  );
}
export function VerifyEmailPage() {
  return (
    <>
      <AuthHeading
        title="Подтверждение почты"
        description="Один шаг до вашего списка задач."
      />
      <Suspense fallback={<Loading />}>
        <VerifyEmailAction />
      </Suspense>
    </>
  );
}
export function ForgotPasswordPage() {
  return (
    <>
      <AuthHeading
        title="Забыли пароль?"
        description="Укажите почту. Мы поможем восстановить доступ."
      />
      <RecoveryForm />
    </>
  );
}
export function ResetPasswordPage() {
  return (
    <>
      <AuthHeading
        title="Новый пароль"
        description="Задайте новый пароль для следующего входа."
      />
      <Suspense fallback={<Loading />}>
        <ResetPasswordForm />
      </Suspense>
    </>
  );
}
export function EmailVerifiedPage() {
  return (
    <>
      <AuthHeading
        title="Почта подтверждена"
        description="Теперь можно войти и перейти к своим задачам."
      />
      <Notice tone="success">
        Подтверждение завершено. Для продолжения войдите в аккаунт.
      </Notice>
      <Button asChild>
        <Link href="/login">Перейти ко входу</Link>
      </Button>
    </>
  );
}
export function PasswordUpdatedPage() {
  return (
    <>
      <AuthHeading
        title="Пароль обновлён"
        description="Войдите с новым паролем, чтобы вернуться к задачам."
      />
      <Notice tone="success">
        Новый пароль сохранён. Для продолжения нужен повторный вход.
      </Notice>
      <Button asChild>
        <Link href="/login">Перейти ко входу</Link>
      </Button>
    </>
  );
}
