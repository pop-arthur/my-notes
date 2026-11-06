import { z } from "zod";
export const codePointLength = (value: string) => Array.from(value).length;
// Java String.strip uses Character.isWhitespace, not JavaScript trim (e.g. NBSP).
export const strip = (value: string) =>
  value.replace(
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    "",
  );
const bounded = (min: number, max: number, message: string) =>
  z
    .string()
    .refine(
      (value) => codePointLength(value) >= min && codePointLength(value) <= max,
      message,
    );
export const emailSchema = z
  .string()
  .transform((value) => strip(value).toLowerCase())
  .pipe(z.email("Введите корректный email").max(254, "Не более 254 символов"));
export const newPasswordSchema = bounded(12, 128, "От 12 до 128 символов");
export const loginSchema = z.object({
  email: emailSchema,
  password: bounded(1, 128, "Введите пароль, не более 128 символов"),
});
export const registerSchema = z.object({
  email: emailSchema,
  password: newPasswordSchema,
});
export const emailFormSchema = z.object({ email: emailSchema });
export const resetSchema = z.object({ newPassword: newPasswordSchema });
export const taskSchema = z.object({
  title: z
    .string()
    .transform(strip)
    .pipe(bounded(1, 200, "Введите от 1 до 200 символов"))
    .refine((value) => !value.includes("\0"), "Недопустимый символ в названии"),
});
