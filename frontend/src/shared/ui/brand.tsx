import Link from "next/link";
import { Check } from "lucide-react";
export function Brand() {
  return (
    <Link
      href="/"
      className="relative inline-flex min-h-7 shrink-0 after:absolute after:inset-x-0 after:-inset-y-2 items-center gap-2.5 rounded-md font-semibold"
    >
      <span className="flex size-7 items-center justify-center rounded-[6px] bg-primary text-primary-foreground">
        <Check size={18} aria-hidden />
      </span>
      <span className="text-lg">Список задач</span>
    </Link>
  );
}
