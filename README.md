# Vienna Pulse

[![backend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml)
[![frontend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml)

A live map of the Vienna public transport network, built on the Wiener Linien GTFS feed.

| Folder | What it is |
| --- | --- |
| [`vienna-pulse-backend`](vienna-pulse-backend) | Spring Boot (Java 25): downloads and reads the GTFS feed every morning; will store it and serve the timetable |
| [`vienna-pulse-frontend`](vienna-pulse-frontend) | Angular 22 (TypeScript): a full-screen map of Vienna; does not use the backend yet |

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

The frontend shows a full-screen map of Vienna and nothing else yet: no transit data and no
calls to the backend. [MapLibre GL JS](https://maplibre.org) draws the map, with
[OpenFreeMap](https://openfreemap.org)'s light Positron style, which needs no API key. The
map keeps MapLibre's own mouse, touch and keyboard controls. The whole view is one component,
[`map-view.ts`](vienna-pulse-frontend/src/app/map-view/map-view.ts).

## Deployment

The frontend runs at [pulse.vladdancea.com](https://pulse.vladdancea.com) on Cloudflare Workers
Static Assets. Frontend changes to `main` deploy after the checks pass; `/api/*` continues to
reach the backend on Hetzner through its Cloudflare tunnel. See [`deploy/README.md`](deploy/README.md)
for credentials, manual deployments and rollback.
