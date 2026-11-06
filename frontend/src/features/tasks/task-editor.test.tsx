import { it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TaskEditor } from "./task-editor";
import { resetCsrf } from "@/shared/api/client";
const task = {
  id: "task-1",
  title: "Старый текст",
  completed: true,
  createdAt: "2026-11-06T00:00:00Z",
  updatedAt: "2026-11-06T00:00:00Z",
};
it("focuses the editor, cancels with Escape and does not write", async () => {
  const user = userEvent.setup();
  const done = vi.fn();
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <TaskEditor task={task} userId="user" onDone={done} onSaved={vi.fn()} />
    </QueryClientProvider>,
  );
  const input = screen.getByRole("textbox", { name: "Название задачи" });
  expect(input).toHaveFocus();
  await user.type(input, " изменён");
  await user.keyboard("{Escape}");
  expect(done).toHaveBeenCalledOnce();
  expect(fetcher).not.toHaveBeenCalled();
});
it("keeps unsaved text after a failed PATCH", async () => {
  resetCsrf();
  const user = userEvent.setup();
  const done = vi.fn();
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: "internal_error" }), {
          status: 500,
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <TaskEditor task={task} userId="user" onDone={done} onSaved={vi.fn()} />
    </QueryClientProvider>,
  );
  const input = screen.getByRole("textbox", { name: "Название задачи" });
  await user.clear(input);
  await user.type(input, "Несохранённый текст");
  await user.click(screen.getByRole("button", { name: "Сохранить" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Не удалось выполнить действие",
  );
  expect(input).toHaveValue("Несохранённый текст");
  expect(done).not.toHaveBeenCalled();
});
