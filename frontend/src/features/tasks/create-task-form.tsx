"use client";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTaskCommand } from "./use-task-command";
import { taskSchema } from "@/shared/lib/validation";
import { applyFieldErrors } from "@/shared/lib/form-errors";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Button } from "@/shared/ui/button";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function CreateTaskForm({
  userId,
  onSuccess,
}: {
  userId: string;
  onSuccess: () => void;
}) {
  const form = useForm<z.infer<typeof taskSchema>>({
    resolver: zodResolver(taskSchema),
    defaultValues: { title: "" },
  });
  const mutation = useTaskCommand(userId);
  return (
    <form
      noValidate
      onSubmit={form.handleSubmit(async ({ title }) => {
        try {
          await mutation.mutateAsync({ kind: "create", title });
          form.reset();
          form.setFocus("title");
          onSuccess();
        } catch (error) {
          applyFieldErrors(error, form.setError, ["title"]);
        }
      })}
      className="space-y-2"
    >
      <Field
        id="new-task"
        label="Новая задача"
        error={form.formState.errors.title?.message}
      >
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
          <Input
            {...form.register("title")}
            {...fieldAccessibility(
              "new-task",
              form.formState.errors.title?.message,
            )}
            aria-describedby={[
              "task-hint",
              form.formState.errors.title && "new-task-error",
            ]
              .filter(Boolean)
              .join(" ")}
            placeholder="Что нужно сделать?"
            disabled={mutation.isPending}
            className="min-w-0 sm:flex-1"
          />
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Добавляем…" : "Добавить"}
          </Button>
        </div>
      </Field>
      <p id="task-hint" className="text-xs text-muted-foreground">
        Enter — добавить · От 1 до 200 символов
      </p>
      <ApiErrorNotice error={mutation.error} />
    </form>
  );
}
