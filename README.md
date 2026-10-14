# Vienna Pulse

A live map of the Vienna public transport network, built on the Wiener Linien GTFS feed.

| Folder | What it is |
| --- | --- |
| [`vienna-pulse-backend`](vienna-pulse-backend) | Spring Boot (Java 25): will import the GTFS feed and serve the timetable |
| `vienna-pulse-frontend` | The map (not started yet) |

## Run

The toolchain is managed by [mise](https://mise.jdx.dev): the root `mise.toml` pins the tool
versions for every project, and each project's `mise.toml` defines its own tasks.

```bash
mise trust --all
mise run dev     # backend at http://localhost:8080
mise run check   # everything CI checks, in every project
```
