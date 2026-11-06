import { api } from "@/shared/api/client";
import type { components } from "@/shared/api/schema";
type Schemas = components["schemas"];
export const authApi = {
  login: (body: Schemas["LoginRequest"]) =>
    api.mutate<Schemas["User"]>("/api/auth/login", "POST", body, true),
  register: (body: Schemas["RegisterRequest"]) =>
    api.mutate<Schemas["Message"]>("/api/auth/register", "POST", body),
  verify: (body: Schemas["TokenRequest"]) =>
    api.mutate<void>("/api/auth/verify-email", "POST", body),
  resend: (body: Schemas["EmailRequest"]) =>
    api.mutate<Schemas["Message"]>(
      "/api/auth/resend-verification",
      "POST",
      body,
    ),
  forgot: (body: Schemas["EmailRequest"]) =>
    api.mutate<Schemas["Message"]>("/api/auth/forgot-password", "POST", body),
  reset: (body: Schemas["ResetPasswordRequest"]) =>
    api.mutate<void>("/api/auth/reset-password", "POST", body, true),
  logout: () => api.mutate<void>("/api/auth/logout", "POST", undefined, true),
};
