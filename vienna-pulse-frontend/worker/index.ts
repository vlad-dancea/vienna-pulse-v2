/** Forwards dynamic requests through the existing Cloudflare Tunnel origin. */
export default {
  fetch(request: Request) {
    // A route's same-URL subrequest follows the tunnel DNS without re-entering this Worker.
    return fetch(request);
  },
};
