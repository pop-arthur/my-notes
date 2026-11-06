import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@/shared/api/error";
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
) {
  if (!(error instanceof ApiError)) return;
  for (const field of fields)
    if (error.fields.some((item) => item.field === field))
      setError(field, { type: "server", message: "Проверьте значение поля" });
}
