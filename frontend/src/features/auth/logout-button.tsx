"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { taskKeys } from "@/entities/task/api";
import { authApi } from "./api";
import { Button } from "@/shared/ui/button";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
export function LogoutButton() {
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: authApi.logout,
    onSuccess: async () => {
      await client.cancelQueries();
      client.removeQueries({ queryKey: taskKeys.all });
      // A new document discards the entire private cache and in-memory form state.
      window.location.replace("/login");
    },
  });
  return (
    <>
      <Button
        variant="ghost"
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? "Выходим…" : "Выйти"}
      </Button>
      {mutation.error && (
        <div className="fixed right-5 bottom-5 left-5 z-50 sm:left-auto sm:max-w-96">
          <ApiErrorNotice error={mutation.error} />
        </div>
      )}
    </>
  );
}
