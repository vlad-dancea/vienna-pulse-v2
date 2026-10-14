# Vienna Pulse

[![backend](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml/badge.svg)](https://github.com/vlad-dancea/vienna-pulse-v2/actions/workflows/backend.yml)

A live map of the Vienna public transport network, built on the Wiener Linien GTFS feed.

| Folder | What it is |
| --- | --- |
| [`vienna-pulse-backend`](vienna-pulse-backend) | Spring Boot (Java 25): downloads and reads the GTFS feed every morning; will store it and serve the timetable |
| `vienna-pulse-frontend` | The map (not started yet) |

## Run

The toolchain is managed by [mise](https://mise.jdx.dev): the root `mise.toml` pins the tool
versions for every project, and each project's `mise.toml` defines its own tasks.

```bash
mise trust --all
mise run dev     # backend at http://localhost:8080, restarts on every save
mise run check   # everything CI checks, in every project
```
