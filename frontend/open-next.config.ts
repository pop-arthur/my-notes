import { defineCloudflareConfig } from "@opennextjs/cloudflare";
// Private data is fetched through the uncached API; no ISR/cache infrastructure.
export default defineCloudflareConfig({});
