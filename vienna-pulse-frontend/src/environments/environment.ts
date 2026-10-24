/** Settings the production build bakes in; `ng serve` and tests use environment.development.ts. */
export const environment = {
  // A hostname without a Cloudflare Worker, so API requests do not count against the Workers quota.
  apiOrigin: 'https://pulse-api.vladdancea.com',
};
