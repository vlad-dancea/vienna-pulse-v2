/** Settings that differ between production and Previews. */
interface Env {
  /** Origin of the backend API; only Previews set it. */
  readonly API_ORIGIN?: string;
}

/** Forwards dynamic requests through the existing Cloudflare Tunnel origin. */
export default {
  fetch(request: Request, env: Env) {
    // A route's same-URL subrequest follows the tunnel DNS without re-entering this Worker.
    if (env.API_ORIGIN === undefined) return fetch(request);
    // A Preview hostname resolves to the Preview itself, so the API is reached through production's.
    const { pathname, search } = new URL(request.url);
    return fetch(new Request(new URL(pathname + search, env.API_ORIGIN), request));
  },
};
