import { describe, it, expect } from "vitest";
import {
  loginSchema,
  newPasswordSchema,
  taskSchema,
  registerSchema,
  strip,
} from "./validation";
describe("contract validation", () => {
  it("normalizes email without rewriting Gmail addresses or passwords", () => {
    const result = registerSchema.parse({
      email: "  A.B+tag@example.com  ",
      password: "  long password  ",
    });
    expect(result.email).toBe("a.b+tag@example.com");
    expect(result.password).toBe("  long password  ");
  });
  it("counts Unicode code points like the backend", () => {
    expect(newPasswordSchema.safeParse("😀".repeat(12)).success).toBe(true);
    expect(newPasswordSchema.safeParse("😀".repeat(11)).success).toBe(false);
    expect(taskSchema.safeParse({ title: "😀".repeat(200) }).success).toBe(
      true,
    );
    expect(taskSchema.safeParse({ title: "😀".repeat(201) }).success).toBe(
      false,
    );
  });
  it("rejects empty, oversized and NUL task titles", () => {
    for (const title of [" ", "\n\t", "x".repeat(201), "hello\0"])
      expect(taskSchema.safeParse({ title }).success).toBe(false);
    expect(taskSchema.parse({ title: "  Купить книгу  " }).title).toBe(
      "Купить книгу",
    );
  });
  it("matches Java strip for NBSP and control whitespace", () => {
    expect(strip("\u00a0hello\u00a0")).toBe("\u00a0hello\u00a0");
    expect(strip("\u001chello\u3000")).toBe("hello");
  });
  it("does not apply registration password minimum at login", () => {
    expect(
      loginSchema.safeParse({ email: "a@example.com", password: "short" })
        .success,
    ).toBe(true);
    expect(
      registerSchema.safeParse({ email: "a@example.com", password: "short" })
        .success,
    ).toBe(false);
  });
});
