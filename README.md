# Vienna Pulse

[![backend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml)
[![frontend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/frontend.yml)

A live map of the Vienna public transport network, built on the Wiener Linien GTFS feed.

| Folder | What it is |
| --- | --- |
| [`vienna-pulse-backend`](vienna-pulse-backend) | Spring Boot (Java 25): downloads and reads the GTFS feed every morning; will store it and serve the timetable |
| [`vienna-pulse-frontend`](vienna-pulse-frontend) | Angular 22 (TypeScript): a neutral gray OpenFreeMap map of Vienna, with Tailwind CSS |

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
the production build and Vitest tests.

The repository root includes shared agent instructions and Angular MCP configuration for
Claude Code, Codex and Agents. The MCP server uses the project's pinned CLI, install
dependencies first with `mise run //vienna-pulse-frontend:install`.

## Map view

The frontend displays Vienna with OpenFreeMap's light, neutral Positron style, rendered by
MapLibre GL JS. No API key is required. Drag the map to pan, use the shared controls to
change zoom or tilt, or reset to central Vienna. Camera changes are immediate, with no
added animations or transit overlays.
