"use client";
import { useCallback, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, ClipboardList } from "lucide-react";
import { taskOptions } from "@/entities/task/api";
import { CreateTaskForm } from "@/features/tasks/create-task-form";
import { TaskRow } from "@/features/tasks/task-row";
import { Loading, Notice } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
import { Button } from "@/shared/ui/button";
import { SuccessToast } from "@/shared/ui/success-toast";
export function TaskBoard({ userId }: { userId: string }) {
  const tasks = useQuery(taskOptions(userId));
  const [editingId, setEditingId] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState<{
    id: number;
    message: string;
  } | null>(null);
  const announcementId = useRef(0);
  const announce = (message: string) => {
    setAnnouncement({ id: ++announcementId.current, message });
  };
  const dismissAnnouncement = useCallback(() => setAnnouncement(null), []);
  const remaining = tasks.data?.filter((task) => !task.completed).length;
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-204 flex-col gap-7 px-5 pt-8 pb-16 sm:px-12 sm:pt-14"
    >
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold sm:text-3xl">Мои задачи</h1>
        <p className="min-h-5 text-muted-foreground">
          {remaining !== undefined
            ? `Осталось выполнить: ${remaining}`
            : "Ваш личный список задач"}
        </p>
      </div>
      <CreateTaskForm
        userId={userId}
        onSuccess={() => announce("Задача добавлена")}
      />
      {tasks.isPending ? (
        <Loading label="Загружаем задачи…" />
      ) : tasks.isError ? (
        <div className="space-y-3">
          <ApiErrorNotice error={tasks.error} />
          <Button
            variant="outline"
            disabled={tasks.isFetching}
            onClick={() => void tasks.refetch()}
          >
            Повторить
          </Button>
        </div>
      ) : tasks.data.length === 0 ? (
        <div className="flex min-h-60 flex-col items-center justify-center gap-3 rounded-lg border bg-card p-8 text-center">
          <ClipboardList
            size={32}
            className="text-muted-foreground"
            aria-hidden
          />
          <h2 className="text-xl font-semibold">Здесь появятся ваши задачи</h2>
          <p className="text-muted-foreground">
            Добавьте первую — начните с малого.
          </p>
        </div>
      ) : (
        <>
          {remaining === 0 && (
            <Notice tone="success">
              <span className="inline-flex items-center gap-2">
                <CheckCircle2 size={18} aria-hidden />
                Все задачи выполнены. Можно выдохнуть!
              </span>
            </Notice>
          )}
          <ul
            aria-label="Список задач"
            className="overflow-hidden rounded-lg border bg-card"
          >
            {tasks.data.map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                userId={userId}
                editingId={editingId}
                onEdit={setEditingId}
                onAnnounce={announce}
              />
            ))}
          </ul>
        </>
      )}
      <p className="text-xs text-muted-foreground">
        {editingId
          ? "Завершите редактирование этой задачи, чтобы открыть другую."
          : "Новые задачи появляются сверху. Выполненные остаются на своём месте."}
      </p>
      {announcement && (
        <SuccessToast
          key={announcement.id}
          message={announcement.message}
          onDismiss={dismissAnnouncement}
        />
      )}
    </main>
  );
}
