# Production

The frontend is served from Cloudflare Workers Static Assets at `https://pulse.vladdancea.com`.
The Worker route in [`wrangler.json`](../vienna-pulse-frontend/wrangler.json) runs in front of the
existing tunnel DNS record. Static files and Angular navigation stay at Cloudflare's edge;
`/api` and `/api/*` go through the Worker to the tunnel. Explicit API routing prevents a browser
navigation to an API endpoint from receiving Angular's HTML fallback.

The backend runs on one Hetzner Cloud server (CX23: 2 vCPU, 4 GB RAM, Debian 13, x86_64) as the
Docker Compose stack in [`compose.yaml`](compose.yaml): the backend, PostgreSQL and a Cloudflare
tunnel. The server has no open ports to the internet:

- Visitors reach `https://pulse.vladdancea.com/api/*` through Cloudflare, which forwards over the
  tunnel that cloudflared opened from the server. Cloudflare terminates TLS.
- Administrators and CI reach SSH over [Tailscale](https://tailscale.com).

## How a deploy works

Every push to `main` that touches the frontend runs `.github/workflows/frontend.yml`:

1. `check` runs `mise run //vienna-pulse-frontend:check`: formatting, typed ESLint, Angular tests,
   production build, Worker type checking and packaging, and integration tests in Cloudflare's
   local runtime. The integration tests use a temporary local HTTP origin to verify API
   forwarding without depending on production.
2. `deploy` downloads the checked static files and bundled Worker, then deploys that artifact
   with the pinned Wrangler CLI. Production deployments run one at a time.
3. The smoke test checks that the public JavaScript and map worker match the build and that
   `/api/stops` still serves GeoJSON through the tunnel.

Pull requests run the same checks without deploying. Frontend deployments do not restart the
backend. PR preview hosting will be added separately.

Every push to `main` that touches the backend runs `.github/workflows/backend.yml`:

1. `check` runs the same checks as `mise run check` and builds the boot jar.
2. `image` packages that jar into a container image, pushes it to
   `ghcr.io/vlad-dancea/vienna-pulse-v2-backend` with an SBOM and a signed provenance
   attestation, and outputs the image digest.
3. `deploy` joins the tailnet as `tag:ci` and runs `ssh deploy@<server> "deploy <commit> <digest>"`.
   The deploy user's key can only run [`vps/pulse-deploy`](vps/pulse-deploy), which checks that the
   commit is on `main`, fetches `compose.yaml` from that commit, pulls the images, starts them, waits
   until every container is healthy and rolls back if they are not. The job then checks the public
   API through Cloudflare.

A deploy interrupts the API for about ten seconds, while the new backend starts. Database migrations run when the backend starts, and
a rollback only swaps the image back, so migrations must keep working with the previous release.

## Day-to-day

From the repository root, with Wrangler authenticated (`mise exec -- pnpm --dir
vienna-pulse-frontend exec wrangler login`), or with `CLOUDFLARE_ACCOUNT_ID` and
`CLOUDFLARE_API_TOKEN` in the environment:

```sh
mise run //vienna-pulse-frontend:check
mise run //vienna-pulse-frontend:deploy
mise run //vienna-pulse-frontend:smoke
```

`deploy` uploads the artifact produced by `check`; it does not rebuild it. Cloudflare deploys
the Worker and its assets together. To restore a previous frontend version, run `mise exec --
pnpm --dir vienna-pulse-frontend exec wrangler rollback` and select that version. Worker logs
are available in the Cloudflare dashboard or through `wrangler tail`.

On the server (`ssh hetzner`), in `/opt/vienna-pulse`:

| Task | Command |
| --- | --- |
| State of the stack | `docker compose ps` |
| Backend logs (JSON, one line per event) | `docker compose logs --tail 100 backend` |
| Deploy history | `cat deploys.log`, full output in `/var/log/pulse-deploy.log` |
| Memory use | `docker stats --no-stream` |
| Query the database | `docker compose exec postgres psql -U pulse -d pulse` |
| Redeploy a commit by hand | `SSH_ORIGINAL_COMMAND="deploy <commit> <digest>" /usr/local/sbin/pulse-deploy` |

The digest of each published image is on the package page on GitHub and in `deploys.log`.

The server installs Debian security updates by itself and reboots at 03:30 UTC when one needs it.
Docker and Tailscale come from their own package repositories and are updated by hand:
`apt update && apt upgrade`.

## Setting up frontend hosting

Keep the existing proxied tunnel CNAME for `pulse.vladdancea.com`. Deploy the frontend as a
Worker **route**, not a Worker Custom Domain: the route's `fetch(request)` follows that CNAME
to the backend origin. Replacing it with a Custom Domain would remove this origin path.

In GitHub's existing `production` environment (restricted to `main`), configure:

- Variable `CLOUDFLARE_ACCOUNT_ID`: the Cloudflare account containing `vladdancea.com`.
- Secret `CLOUDFLARE_API_TOKEN`: a dedicated token with Account > Workers Scripts > Edit for
  that account, plus Zone > Workers Routes > Edit and Zone > Zone > Read for `vladdancea.com`.

Create the token from [Cloudflare's API tokens page](https://dash.cloudflare.com/profile/api-tokens).
The account and zone resource restrictions matter: this token does not need DNS editing,
tunnel administration or access to other zones. Store the token directly in GitHub, never in
the repository. Local Wrangler OAuth credentials are separate from the CI token.

Run `check`, `deploy` and `smoke` above for the initial deployment. Subsequent frontend pushes
to `main` deploy automatically. Cloudflare manages TLS using the hostname's existing setup.

## Setting up a new backend server

These steps rebuild production from nothing. Everything else is in this repository.

1. **Server.** Debian 13 with Docker Engine (from Docker's apt repository) and Tailscale, joined
   to the tailnet and tagged `tag:vps`. Then, as root:

   ```sh
   tailscale set --accept-dns=false   # the public site must not depend on Tailscale's DNS
   timedatectl set-timezone Etc/UTC
   install -d /etc/systemd/journald.conf.d
   printf '[Journal]\nSystemMaxUse=300M\n' > /etc/systemd/journald.conf.d/50-size.conf
   printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "03:30";\n' \
     > /etc/apt/apt.conf.d/52automatic-reboot
   ```

2. **Tailscale policy.** `tag:ci` may reach `tag:vps` on `tcp:22` and nothing else. Tagging an
   existing machine keeps its key expiry, so disable that on the server's machine page: with the
   public SSH port closed, an expired key would leave only Hetzner's rescue system. A federated
   identity (issuer GitHub, scope `auth_keys`, tag `tag:ci`) lets the deploy job join without a
   stored secret. Its subject is
   `repo:vlad-dancea@136276333/vienna-pulse-v2@1418750148:environment:production`: this repository
   uses GitHub's immutable subjects, which name the owner and repository by their numeric IDs, so
   a repository that later takes the same name cannot match. Tailscale cannot change a
   credential's subject afterwards; a wrong one has to be revoked and created again.

3. **Cloudflare tunnel.** A remotely managed tunnel with one route, `pulse.vladdancea.com` path
   `^/api/` to `http://backend:8080`, then `http_status:404` for everything else, and a proxied
   CNAME from `pulse.vladdancea.com` to `<tunnel id>.cfargotunnel.com`.

4. **Secrets.** Files in `/opt/vienna-pulse/secrets/` (folder `0700`, files `0444`, so the
   containers' unprivileged users can read them). Never overwrite the database passwords: the
   database only reads them when it is first created.

   ```sh
   install -d -m 0755 /opt/vienna-pulse && install -d -m 0700 /opt/vienna-pulse/secrets
   cd /opt/vienna-pulse/secrets
   for f in db_admin_password db_app_password; do
     [ -e "$f" ] || { head -c 32 /dev/urandom | base64 | tr -d '\n+/=' > "$f"; chmod 0444 "$f"; }
   done
   # The tunnel token, from the tunnel's page or `cf tunnels token get <id>`, piped in, never echoed:
   install -m 0444 /dev/stdin tunnel_token
   ```

5. **Deploy user.** Generate an ed25519 key pair on a laptop, store the private key as the
   `VPS_DEPLOY_KEY` secret of the `production` environment (deployments from `main` only), copy
   `vps/` to the server and run `setup-deploy-user.sh "<public key>"` there as root. The
   environment also needs the variables `VPS_HOST` (the server's Tailscale IP), `VPS_KNOWN_HOSTS`
   (`<ip> <contents of /etc/ssh/ssh_host_ed25519_key.pub without the comment>`),
   `TS_OAUTH_CLIENT_ID` and `TS_AUDIENCE`.

6. **Firewall.** A Hetzner Cloud Firewall that allows only ICMP in, for IPv4 and IPv6. Tailscale
   and the tunnel both connect outwards, so nothing else needs to come in.

Then rerun the latest `backend` workflow on `main`. On an empty database the backend imports the
feed right after it starts.
