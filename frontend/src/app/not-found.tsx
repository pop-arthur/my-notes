import Link from "next/link";
import { Button } from "@/shared/ui/button";
export default function NotFound() {
  return (
    <main id="main" className="mx-auto max-w-110 space-y-5 px-5 py-20">
      <h1 className="text-2xl font-semibold">Страница не найдена</h1>
      <p>Возможно, ссылка устарела.</p>
      <Button asChild>
        <Link href="/">К моим задачам</Link>
      </Button>
    </main>
  );
}
