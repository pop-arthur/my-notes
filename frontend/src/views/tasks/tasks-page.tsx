"use client";
import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { sessionOptions } from "@/entities/session/api";
import { TaskBoard } from "@/widgets/task-board/task-board";
import { SiteHeader } from "@/widgets/site-header/site-header";
import { LogoutButton } from "@/features/auth/logout-button";
import { Loading } from "@/shared/ui/notice";
import { ApiErrorNotice } from "@/shared/ui/api-error-notice";
import { Button } from "@/shared/ui/button";
export function TasksPage() {
  const user = useQuery(sessionOptions);
  const router = useRouter();
  const hadSession = useRef(false);
  useEffect(() => {
    if (user.data) {
      hadSession.current = true;
      return;
    }
    if (user.isSuccess)
      router.replace(
        hadSession.current ? "/login?reason=session-expired" : "/login",
      );
  }, [user.isSuccess, user.data, router]);
  if (!user.data)
    return (
      <>
        <SiteHeader />
        <main id="main" className="mx-auto max-w-110 space-y-4 px-5 py-20">
          {user.isError ? (
            <>
              <ApiErrorNotice error={user.error} />
              <Button
                variant="outline"
                onClick={() => void user.refetch()}
                disabled={user.isFetching}
              >
                Повторить
              </Button>
            </>
          ) : (
            <Loading label="Проверяем сессию…" />
          )}
        </main>
      </>
    );
  return (
    <>
      <SiteHeader email={user.data.email} account={<LogoutButton />} />
      <TaskBoard key={user.data.id} userId={user.data.id} />
    </>
  );
}
