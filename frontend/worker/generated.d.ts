// OpenNext emits this module at build time. No generated implementation is committed.
declare module "*/.open-next/worker.js" {
  const handler: {
    fetch(
      request: Request,
      env: CloudflareEnv,
      context: unknown,
    ): Promise<Response>;
  };
  export default handler;
}
