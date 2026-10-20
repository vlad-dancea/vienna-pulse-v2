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
routes run on today's service date. These are timetable routes, without live vehicle positions. Stop loading and upgrade status appear in the map panel only while needed, with a retry button
when the stops request fails.

Stop modes come from `stop_times.txt`, trips and routes. The importer classifies every platform
before swapping the timetable into place. An existing database starts a new import after the
stop-mode schema upgrade. While the backend is upgrading or importing for the first time, the
frontend keeps available stops visible and rechecks once a minute until classifications arrive.

In development, the Angular dev server forwards `/api` to the backend on port 8080. The map keeps
MapLibre's own mouse, touch and keyboard controls. The view is in
[`map-view.ts`](vienna-pulse-frontend/src/app/map-view/map-view.ts).

Vienna's 23 Bezirke have dashed boundaries and numbered names, replacing the base map's
neighborhood labels inside and outside the city. Street, town and village names stay
visible above the boundaries. District names appear from zoom 12 and fade out between
zoom 14 and 15. Wider views show district numbers. The bundled geometry comes from
[Stadt Wien's district boundaries](https://www.data.gv.at/katalog/dataset/2ee6b8bf-6292-413c-bb8b-bd22dbb2ad4b/resource/f5ad0704-ceca-4c46-87f2-f2e131eee44b)
under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Datenquelle: Stadt Wien - https://data.wien.gv.at.

The snapshot was downloaded on 2026-10-20, with source update date 2026-09-01. It is
simplified to a 5-meter interval and coordinates rounded to five decimal places. Label
anchors are interior points of the simplified polygons. To refresh both assets from the
repository root:

```sh
district_tmp=$(mktemp -d)
curl --fail --location 'https://data.wien.gv.at/daten/geo?service=WFS&request=GetFeature&version=1.1.0&typeName=ogdwien:BEZIRKSGRENZEOGD&srsName=EPSG:4326&outputFormat=json' -o "$district_tmp/districts.json"
mise exec -- pnpm dlx mapshaper@0.7.36 \
  -i "$district_tmp/districts.json" \
  -filter-fields BEZNR,NAMEK -rename-fields number=BEZNR,name=NAMEK \
  -simplify interval=5 keep-shapes \
  -o vienna-pulse-frontend/public/data/vienna-districts.geojson format=geojson precision=0.00001 \
  -points inner \
  -o vienna-pulse-frontend/public/data/vienna-district-labels.geojson format=geojson precision=0.00001
rm -r "$district_tmp"
```

## Deployment

The frontend runs at [pulse.vladdancea.com](https://pulse.vladdancea.com) on Cloudflare Workers
Static Assets. Frontend changes to `main` deploy after the checks pass, and each pull request
that changes the frontend gets a preview at `pr-<number>.pulse.vladdancea.com`. `/api/*` continues
to reach the backend on Hetzner through its Cloudflare tunnel. See [`deploy/README.md`](deploy/README.md)
for credentials, previews, manual deployments and rollback.
