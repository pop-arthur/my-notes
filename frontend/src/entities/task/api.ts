import { queryOptions } from "@tanstack/react-query";
import { api } from "@/shared/api/client";
import type { components } from "@/shared/api/schema";
export type Task = components["schemas"]["Task"];
export const taskKeys = {
  all: ["tasks"] as const,
  list: (userId: string) => ["tasks", userId] as const,
};
export const taskOptions = (userId: string) =>
  queryOptions({
    queryKey: taskKeys.list(userId),
    queryFn: ({ signal }) =>
      api.get<components["schemas"]["TaskList"]>("/api/tasks", signal),
    staleTime: 15_000,
    retry: false,
  });
export const taskApi = {
  create: (body: components["schemas"]["CreateTaskRequest"]) =>
    api.mutate<Task>("/api/tasks", "POST", body),
  update: (id: string, body: components["schemas"]["UpdateTaskRequest"]) =>
    api.mutate<Task>(`/api/tasks/${encodeURIComponent(id)}`, "PATCH", body),
  delete: (id: string) =>
    api.mutate<void>(`/api/tasks/${encodeURIComponent(id)}`, "DELETE"),
};
