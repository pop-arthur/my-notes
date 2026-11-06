import type { Metadata } from "next";
import { QueryProvider } from "./providers/query-provider";
import "./globals.css";
export const metadata: Metadata = {
  title: { default: "Список задач", template: "%s · Список задач" },
  description: "Ваши задачи — в одном месте.",
  robots: { index: false, follow: false },
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru">
      <body>
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-card focus:p-4"
        >
          К содержимому
        </a>
        <QueryProvider>{children}</QueryProvider>
      </body>
    </html>
  );
}
