"use client";
import { useEffect, useState, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { sessionEvents } from "@/shared/api/client";
import { sessionKey } from "@/entities/session/api";
import { taskKeys } from "@/entities/task/api";
export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: true },
          mutations: { retry: false, gcTime: 0 },
        },
      }),
  );
  useEffect(
    () =>
      sessionEvents.subscribe(() => {
        void client.cancelQueries({ queryKey: taskKeys.all });
        client.removeQueries({ queryKey: taskKeys.all });
        client.setQueryData(sessionKey, null);
      }),
    [client],
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
