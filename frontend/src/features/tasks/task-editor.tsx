"use client";
import { useEffect } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import type { Task } from "@/entities/task/api";
import { useTaskCommand } from "./use-task-command";
import { codePointLength, strip, taskSchema } from "@/shared/lib/validation";
import { applyFieldErrors } from "@/shared/lib/form-errors";
import { Field, fieldAccessibility } from "@/shared/ui/field";
import { Textarea } from "@/shared/ui/input";
import { Button } from "@/shared/ui/button";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function TaskEditor({
  task,
  userId,
  onDone,
  onSaved,
}: {
  task: Task;
  userId: string;
  onDone: () => void;
  onSaved: () => void;
}) {
  const form = useForm<z.infer<typeof taskSchema>>({
    resolver: zodResolver(taskSchema),
    defaultValues: { title: task.title },
    mode: "onChange",
  });
  const title = useWatch({ control: form.control, name: "title" });
  const mutation = useTaskCommand(userId);
  useEffect(() => {
    form.setFocus("title");
    const field = document.getElementById(`edit-${task.id}`);
    if (field instanceof HTMLTextAreaElement) {
      field.focus();
      field.setSelectionRange(field.value.length, field.value.length);
    }
  }, [form, task.id]);
  const id = `edit-${task.id}`;
  return (
    <form
      noValidate
      className="min-w-0 flex-1 space-y-3"
      onSubmit={form.handleSubmit(async (values) => {
        if (strip(values.title) === task.title) return;
        try {
          await mutation.mutateAsync({
            kind: "update",
            id: task.id,
            changes: { title: values.title },
          });
          onSaved();
          onDone();
        } catch (error) {
          applyFieldErrors(error, form.setError, ["title"]);
        }
      })}
      onKeyDown={(event) => {
        if (event.nativeEvent.isComposing || mutation.isPending) return;
        if (event.key === "Escape") {
          event.preventDefault();
          onDone();
        }
        if (event.key === "Enter" && !event.shiftKey) {
          event.preventDefault();
          event.currentTarget.requestSubmit();
        }
      }}
    >
      <div className="relative">
        <Field
          id={id}
          label="Название задачи"
          error={form.formState.errors.title?.message}
        >
          <Textarea
            {...form.register("title")}
            {...fieldAccessibility(id, form.formState.errors.title?.message)}
            aria-describedby={`${id}-hint${form.formState.errors.title ? ` ${id}-error` : ""}`}
            disabled={mutation.isPending}
            rows={2}
          />
        </Field>
        <span
          className="absolute top-0 right-0 text-xs text-muted-foreground"
          aria-hidden
        >
          {codePointLength(title)} / 200
        </span>
      </div>
      <ApiErrorNotice error={mutation.error} />
      <div className="flex flex-wrap gap-2">
        <Button
          type="submit"
          className="flex-1 sm:flex-none"
          disabled={
            mutation.isPending ||
            strip(title) === task.title ||
            !form.formState.isValid
          }
        >
          {mutation.isPending ? "Сохраняем…" : "Сохранить"}
        </Button>
        <Button
          variant="outline"
          className="flex-1 sm:flex-none"
          onClick={onDone}
          disabled={mutation.isPending}
        >
          Отмена
        </Button>
      </div>
      <p id={`${id}-hint`} className="text-xs text-muted-foreground">
        Enter — сохранить · Escape — отменить
      </p>
    </form>
  );
}
