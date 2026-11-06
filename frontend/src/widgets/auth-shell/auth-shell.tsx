import type { ReactNode } from "react";
export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <main
      id="main"
      className="flex justify-center px-5 pt-8 pb-16 sm:px-12 sm:pt-20"
    >
      <section className="flex w-full max-w-110 flex-col gap-5 rounded-lg border bg-card p-6 sm:p-8">
        {children}
      </section>
    </main>
  );
}
export function AuthHeading({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold leading-normal">{title}</h1>
      <p className="text-muted-foreground">{description}</p>
    </div>
  );
}
