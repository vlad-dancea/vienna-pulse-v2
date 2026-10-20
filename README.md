# Vienna Pulse

[![backend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml)
[![frontend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml)

A live map of the Vienna public transport network, built on the Wiener Linien GTFS feed.

| Folder | What it is |
| --- | --- |
| [`vienna-pulse-backend`](vienna-pulse-backend) | Spring Boot (Java 25): imports the GTFS feed into PostgreSQL every morning and serves stops and scheduled lines as GeoJSON |
| [`vienna-pulse-frontend`](vienna-pulse-frontend) | Angular 22 (TypeScript): a full-screen map of Vienna with stops and scheduled routes |

## Run

The toolchain is managed by [mise](https://mise.jdx.dev): the root `mise.toml` pins the tool
versions for every project, and each project's `mise.toml` defines its own tasks. The backend
also needs [Docker](https://docs.docker.com/engine/install/): it starts its PostgreSQL database in
a container.

```bash
mise trust --all
mise run dev     # backend at http://localhost:8080, frontend at http://localhost:4200
mise run check   # everything CI checks, in every project
mise run format  # format the sources of every project
```

mise installs the pinned tools on first use. The frontend installs dependencies from its
committed pnpm lockfile before running a task.

To run one project, use `mise run //vienna-pulse-frontend:dev` or
`mise run //vienna-pulse-backend:dev`. Each project has its own CI workflow running its
`check` task. The frontend checks formatting, typed ESLint rules, template accessibility,
the production build, Vitest tests and Cloudflare runtime integration tests.

The repository root includes shared agent instructions and Angular MCP configuration for
Claude Code, Codex and Agents. The MCP server uses the project's pinned CLI, install
dependencies first with `mise run //vienna-pulse-frontend:install`.

## Map view

The frontend shows Vienna's public transport stops and today's scheduled routes on a full-screen
map. [MapLibre GL JS](https://maplibre.org) draws the map with
[OpenFreeMap](https://openfreemap.org)'s light Positron style, which needs no API key. Stops come
from `/api/stops` and routes from `/api/lines`, using today's service date in Vienna. The date
updates within a minute of midnight and when a suspended tab resumes. Lines use
the feed's colors, with fallback colors when none are supplied, and show their names at closer
zoom levels. The All, U-Bahn, Tram and Bus controls filter routes and stops. Interchanges remain
visible for every mode that serves them. Stop modes cover the full imported timetable, while
routes run on today's service date. These are timetable routes, without live vehicle positions. Loading, empty and unavailable
responses appear in the map panel, with a retry button when the request fails.

Stop modes come from `stop_times.txt`, trips and routes. The importer classifies every platform
before swapping the timetable into place. An existing database starts a new import after the
stop-mode schema upgrade. While the backend is upgrading or importing for the first time, the
frontend keeps available stops visible and rechecks once a minute until classifications arrive.

In development, the Angular dev server forwards `/api` to the backend on port 8080. The map keeps
MapLibre's own mouse, touch and keyboard controls. The view is in
[`map-view.ts`](vienna-pulse-frontend/src/app/map-view/map-view.ts).

## Deployment

The frontend runs at [pulse.vladdancea.com](https://pulse.vladdancea.com) on Cloudflare Workers
Static Assets. Frontend changes to `main` deploy after the checks pass, and each pull request
that changes the frontend gets a preview at `pr-<number>.pulse.vladdancea.com`. `/api/*` continues
to reach the backend on Hetzner through its Cloudflare tunnel. See [`deploy/README.md`](deploy/README.md)
for credentials, previews, manual deployments and rollback.
