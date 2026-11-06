import { z } from "zod";
const problemSchema = z.object({
  code: z.string(),
  errors: z
    .array(z.object({ field: z.string(), message: z.string() }))
    .optional(),
});
const messages: Record<string, string> = {
  invalid_credentials: "Неверная почта или пароль.",
  unauthenticated: "Сессия завершена. Войдите снова.",
  email_not_verified: "Подтвердите почту, чтобы войти в аккаунт.",
  csrf_invalid: "Сессия обновлена. Повторите действие.",
  validation_failed: "Проверьте введённые данные.",
  invalid_token:
    "Ссылка недействительна или срок её действия истёк. Запросите новую.",
  task_not_found: "Задача больше недоступна. Обновите список.",
  rate_limited: "Слишком много запросов. Подождите перед повторной попыткой.",
  mail_delivery_unavailable:
    "Отправка писем пока недоступна. Попробуйте позже.",
  mail_delivery_failed:
    "Не удалось отправить письмо. Попробуйте запросить его ещё раз.",
  google_unavailable: "Вход через Google сейчас недоступен.",
  network_error:
    "Не удалось связаться с сервером. Проверьте подключение и повторите попытку.",
  internal_error: "Не удалось выполнить действие. Попробуйте ещё раз.",
  invalid_response: "Сервер вернул неожиданный ответ. Повторите попытку позже.",
};
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly fields: readonly { field: string; message: string }[] = [],
    public readonly retryAt?: number,
  ) {
    super(messages[code] ?? messages.internal_error);
    this.name = "ApiError";
  }
  static async fromResponse(response: Response) {
    const body: unknown = await response.json().catch(() => null);
    const parsed = problemSchema.safeParse(body);
    const retry = response.headers.get("Retry-After");
    const delay = retry
      ? /^\d+$/.test(retry)
        ? Number(retry) * 1000
        : Date.parse(retry) - Date.now()
      : 0;
    return new ApiError(
      response.status,
      parsed.success ? parsed.data.code : "internal_error",
      parsed.success ? parsed.data.errors : [],
      Number.isFinite(delay) && delay > 0
        ? Date.now() + Math.min(delay, 86400000)
        : undefined,
    );
  }
}
export function errorMessage(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : "Не удалось выполнить действие. Попробуйте ещё раз.";
}
