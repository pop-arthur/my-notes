"use client";
import { Button } from "@/shared/ui/button";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <main id="main" className="mx-auto max-w-110 space-y-5 px-5 py-20">
      <h1 className="text-2xl font-semibold">Не удалось открыть страницу</h1>
      <p>Повторите попытку.</p>
      <Button onClick={reset}>Повторить</Button>
    </main>
  );
}
