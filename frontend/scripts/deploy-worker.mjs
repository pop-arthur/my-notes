import ts from "typescript";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
const parsed = ts.parseConfigFileTextToJson(
  "wrangler.jsonc",
  readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8"),
);
if (
  parsed.error ||
  !parsed.config.vpc_services?.[0]?.service_id ||
  parsed.config.vpc_services[0].service_id ===
    "00000000-0000-0000-0000-000000000000"
)
  throw new Error("Configure a real BACKEND VPC Service ID first");
const result = spawnSync(
  process.execPath,
  [
    new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url)
      .pathname,
    "deploy",
  ],
  { stdio: "inherit" },
);
process.exit(result.status ?? 1);
