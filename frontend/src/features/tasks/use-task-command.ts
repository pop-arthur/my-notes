"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { taskApi, taskKeys, type Task } from "@/entities/task/api";
type Command =
  | { kind: "create"; title: string }
  | {
      kind: "update";
      id: string;
      changes: Partial<Pick<Task, "title" | "completed">>;
    }
  | { kind: "delete"; id: string };
export function useTaskCommand(userId: string) {
  const client = useQueryClient();
  const queryKey = taskKeys.list(userId);
  return useMutation({
    mutationKey: taskKeys.all,
    mutationFn: async (command: Command): Promise<Task | void> => {
      if (command.kind === "create")
        return taskApi.create({ title: command.title });
      if (command.kind === "update")
        return taskApi.update(command.id, command.changes);
      return taskApi.delete(command.id);
    },
    onMutate: () => client.cancelQueries({ queryKey }),
    onSuccess: (task, command) => {
      client.setQueryData<Task[]>(queryKey, (previous) => {
        if (!previous) return previous;
        if (command.kind === "delete")
          return previous.filter((item) => item.id !== command.id);
        if (!task) return previous;
        if (command.kind === "create")
          return [task, ...previous.filter((item) => item.id !== task.id)];
        return previous.map((item) => (item.id === task.id ? task : item));
      });
    },
    onSettled: () => {
      // A refetch between queued writes could overwrite another mutation's response.
      if (client.isMutating({ mutationKey: taskKeys.all }) === 1)
        return client.invalidateQueries({ queryKey });
    },
  });
}
