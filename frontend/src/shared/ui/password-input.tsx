"use client";
import { useState, type ComponentProps } from "react";
import Image from "next/image";
import { EyeOff } from "lucide-react";
import { Input } from "./input";
export function PasswordInput(props: Omit<ComponentProps<"input">, "type">) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <Input
        {...props}
        type={visible ? "text" : "password"}
        className="pr-12"
      />
      <button
        type="button"
        disabled={props.disabled}
        aria-label={visible ? "Скрыть пароль" : "Показать пароль"}
        aria-pressed={visible}
        className="absolute top-px right-px flex h-11.5 w-11 items-center justify-center rounded-md text-muted-foreground"
        onClick={() => setVisible(!visible)}
      >
        {visible ? (
          <EyeOff size={20} aria-hidden />
        ) : (
          <Image src="/figma/eye.svg" width={20} height={20} alt="" />
        )}
      </button>
    </div>
  );
}
