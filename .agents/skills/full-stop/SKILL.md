---
name: full-stop
description: Use when the user says "full stop". Takes the current work to a pull request that is ready to merge, babysits it through CI and CodeRabbit, and never merges.
---

# Full stop

Carry the work to a green, reviewed pull request without the maintainer having to watch. Saying
"full stop" is the maintainer's permission to commit, push and open the pull request. It is not
permission to merge.

Ask the maintainer only when you cannot go on alone: a design decision, a finding you doubt, or a
missing permission. Use the harness's question tool, ask once with everything you need, then keep
going. Everything else follows `AGENTS.md`, which wins over this skill when the two disagree.

## Steps

1. Finish the work and prove it. Run `mise run check` and the verification `AGENTS.md` asks for,
   such as an import of the real feed for ingestion changes.
2. Show it when the change alters what a person sees in the app, such as a look, text, the map,
   motion, or a bug that shows on screen. Skip this step for changes nobody can see.
   - Take screenshots of a look and record a clip of anything that moves or changes over time.
     For a change to something that already exists, capture production
     (https://pulse.vladdancea.com, which runs `main`) as the before. Capture at phone width
     (390 x 844) and desktop width (1280 x 800), with real data from `mise run dev` or the
     Preview. Mock the API only for a state real data cannot show, and say which state is mocked.
   - Use Playwright's headless Chromium from the frontend's `@playwright/test`, launched with
     `--use-angle=swiftshader --enable-unsafe-swiftshader` so the map paints. Take pictures with
     `page.screenshot` and clips with the browser context's `recordVideo`. Close the context
     before the script ends, because Playwright finishes the video then and leaves an empty file
     when the script exits first. Run the script from a `mktemp -d` folder, because relative paths
     land in the working directory. T3 Code's preview tab cannot show the map, because it never
     runs `requestAnimationFrame`.
   - Look at every capture yourself before showing it. Read the screenshots, and pull frames out
     of a clip with `ffmpeg -ss <seconds> -i <clip> -frames:v 1 <frame>.png`. Fix what looks
     wrong, such as wrapped or clipped text, overlap or the wrong state, and capture again. A
     blank map can be headless Chromium's fault, so check the canvas pixels before blaming the
     app.
   - Cut each clip to the seconds that matter, usually under 30, as an MP4 with
     `-c:v libx264 -pix_fmt yuv420p -crf 26 -an`. When timing matters, print the time on the
     clip with ffmpeg's `drawtext` filter.
   - For a look decision left to the maintainer, capture each option the same way, side by side.
   - Capture again after a later commit changes what a person sees, so the report shows the
     final state.
   - GitHub gets no images. Say in the pull request's Verification section what was captured
     and what it showed.
3. Run the `sol-loop` skill on the change. Stop here and ask if a high finding stays disputed.
4. Commit in small commits that each pass `mise run check`, push, and open the pull request
   against `main` as described in `AGENTS.md`. Never open a draft, because CodeRabbit skips
   drafts. If a pull request already exists for the branch, use it.
5. Link the pull request to the thread with `link_pull_request`. If it already existed, handle
   its open review threads and failed checks as in step 6 first, because only new comments wake
   you. Then call `watch_pull_request` and end your turn. T3 Code wakes you when a check fails,
   the required checks pass, someone comments or reviews, or the branch conflicts. Do not poll.
6. On each wake, check the whole pull request, not only the event that woke you.
   `gh pr checks` exits with code 8 while any check is pending, which is not a failure, so run
   it on its own and never before `&&`.
   - A failed check: find why it differs from the local check before retrying. Retry only a
     failure you can show is infrastructure, not code.
   - CodeRabbit and other review comments: handle them as `AGENTS.md` describes. Push the fixes
     and wait for the next review.
   - `main` moved: merge `origin/main` into the branch, run `mise run check` and push.
   - Only the `frontend-preview` status is missing: it is not a required check and does not wake
     you. Find the unfinished runs with
     `gh run list --workflow frontend-preview.yml --event workflow_run`, wait for them with
     `gh run watch <id>`, then check the pull request again.
7. The pull request is ready when:
   - `backend-required`, `frontend-required` and `repository-required` pass on the latest commit
   - `gh pr checks <number>` shows CodeRabbit's "Review completed" for the latest commit
   - every review thread is resolved, or answered with a reason and left for the maintainer
   - the branch contains the latest `main`
   - for frontend changes, the `frontend-preview` status links a deployed preview
8. Call `unwatch_pull_request` and report.

## Report

Write the report so it can be read in one pass:

- the pull request link and, for frontend changes, the preview link
- one line on what changed and how it was verified
- for a change a person can see, the screenshots and clips from step 2, each embedded as
  `![what it shows](/absolute/path)` with one line on what it shows. T3 Code shows images and
  MP4 clips from absolute paths.
- the sol loop and CodeRabbit findings with what happened to each, as a short table
- what is left for the maintainer to decide, if anything
- the next step: "Ready to merge. Say full send or merge it yourself."
