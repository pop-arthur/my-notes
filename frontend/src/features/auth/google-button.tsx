import { Button } from "@/shared/ui/button";
export function GoogleButton({ register = false }: { register?: boolean }) {
  return (
    <>
      <Button
        asChild
        variant="outline"
        className="w-full font-medium text-foreground"
      >
        <a href="/oauth2/authorization/google">
          <span className="text-lg font-bold" aria-hidden>
            G
          </span>
          {register ? "Регистрация через Google" : "Войти через Google"}
        </a>
      </Button>
      <div className="flex items-center gap-3 text-xs text-muted-foreground">
        <span className="h-px flex-1 bg-border" />
        или по почте
        <span className="h-px flex-1 bg-border" />
      </div>
    </>
  );
}
