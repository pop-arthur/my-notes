"use client";
import { useRef } from "react";
import type { Task } from "@/entities/task/api";
import { useTaskCommand } from "./use-task-command";
import { TaskEditor } from "./task-editor";
import { Checkbox } from "@/shared/ui/checkbox";
import { Button } from "@/shared/ui/button";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
import { cn } from "@/shared/lib/cn";
export function TaskRow({
  task,
  userId,
  editingId,
  onEdit,
  onAnnounce,
}: {
  task: Task;
  userId: string;
  editingId: string | null;
  onEdit: (id: string | null) => void;
  onAnnounce: (text: string) => void;
}) {
  const mutation = useTaskCommand(userId);
  const editRef = useRef<HTMLButtonElement>(null);
  const editing = editingId === task.id;
  const locked = editingId !== null || mutation.isPending;
  const finishEdit = () => {
    onEdit(null);
    requestAnimationFrame(() => editRef.current?.focus());
  };
  return (
    <li
      className="border-b px-3 py-3 last:border-b-0 sm:px-4"
      aria-busy={mutation.isPending}
    >
      <div
        className={cn(
          "flex gap-2",
          !editing && "flex-col sm:flex-row sm:items-start sm:gap-3",
        )}
      >
        <div className="flex min-w-0 flex-1 items-start gap-2">
          <Checkbox
            checked={task.completed}
            disabled={locked}
            loading={
              mutation.isPending && mutation.variables?.kind === "update"
            }
            aria-label={`${task.completed ? "Вернуть в невыполненные" : "Отметить выполненной"}: ${task.title}`}
            onCheckedChange={(checked) => {
              if (typeof checked === "boolean")
                mutation.mutate(
                  {
                    kind: "update",
                    id: task.id,
                    changes: { completed: checked },
                  },
                  {
                    onSuccess: () =>
                      onAnnounce(
                        checked
                          ? "Задача выполнена"
                          : "Задача возвращена в невыполненные",
                      ),
                  },
                );
            }}
          />
          {editing ? (
            <TaskEditor
              task={task}
              userId={userId}
              onDone={finishEdit}
              onSaved={() => onAnnounce("Задача сохранена")}
            />
          ) : (
            <p
              className={cn(
                "min-w-0 flex-1 py-2.5 text-base wrap-anywhere",
                task.completed && "text-muted-foreground line-through",
              )}
            >
              {task.title}
            </p>
          )}
        </div>
        {!editing && (
          <div className="flex shrink-0 items-start gap-1 pl-11 sm:pl-0">
            <Button
              ref={editRef}
              variant="ghost"
              disabled={locked}
              aria-label={`Редактировать: ${task.title}`}
              onClick={() => onEdit(task.id)}
            >
              Редактировать
            </Button>
            <Button
              variant="danger"
              disabled={locked}
              aria-label={`Удалить: ${task.title}`}
              onClick={() =>
                mutation.mutate(
                  { kind: "delete", id: task.id },
                  { onSuccess: () => onAnnounce("Задача удалена") },
                )
              }
            >
              {mutation.isPending && mutation.variables?.kind === "delete"
                ? "Удаляем…"
                : "Удалить"}
            </Button>
          </div>
        )}
      </div>
      {editing && (
        <div className="pl-11">
          <Button
            variant="danger"
            disabled
            aria-label={`Удалить: ${task.title}`}
          >
            Удалить
          </Button>
        </div>
      )}
      <ApiErrorNotice error={mutation.error} />
    </li>
  );
}
