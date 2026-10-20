---
name: full-send
description: Use only when the user says "full send". Does everything full-stop does, then squash-merges the pull request and watches the production deploy.
---

# Full send

Saying "full send" is the maintainer's permission to commit, push, open the pull request and merge
it. A merge to `main` ships to production, so merge only when every gate below holds. When one
cannot be met, stop and report as full stop does.

## Steps

1. Run the `full-stop` skill through to its ready state.
2. Check the merge gates once more, on the latest commit:
   - every gate from full stop holds
   - no finding is still disputed and nothing waits for a maintainer decision
   - nobody requested changes in a review
   - the pull request's Verification section covers every change
3. Squash-merge as `AGENTS.md` describes, with the subject `<title> (#<number>)` and a body that
   follows the commit message rules. Never force-push and never enable auto-merge.
4. Watch the workflow runs on `main` for the merge commit until they finish, with
   `gh run list --branch main --commit <sha>` and `gh run watch <id>`. They deploy and smoke
   test production.
5. If a deploy or smoke test fails, report at once with the run link and the failing step, and
   propose a revert pull request. Do not push to `main`, touch the server or retry a deploy
   without the maintainer.

## Report

- the merged pull request and its squash commit
- the deploy result for each project that shipped
- anything that needs the maintainer, such as a failed deploy
