import { queryOptions } from "@tanstack/react-query";
import { api } from "@/shared/api/client";
import { ApiError } from "@/shared/api/error";
import type { components } from "@/shared/api/schema";
export type User = components["schemas"]["User"];
export const sessionKey = ["session", "me"] as const;
export const sessionOptions = queryOptions({
  queryKey: sessionKey,
  queryFn: async ({ signal }) => {
    try {
      return await api.get<User>("/api/auth/me", signal);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return null;
      throw error;
    }
  },
  staleTime: 30_000,
  retry: false,
});
