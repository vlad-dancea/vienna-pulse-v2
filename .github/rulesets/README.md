# Main branch protection

`main.json` is the GitHub branch ruleset applied to this repository. Settings take
effect through GitHub's API, not by committing this file. The active ruleset is
visible under **Settings → Rules → Rulesets**.

Every change to `main` requires a pull request, passing `backend-required` and
`frontend-required` checks from the GitHub Actions app, and a branch up to date
with `main`. All review conversations must be resolved. Force pushes, deletion
and merge commits are blocked. No user, administrator or app has bypass permission.
Squash and rebase merges preserve the linear history.

This repository currently has one maintainer, so the ruleset requires no approving
human review: GitHub does not let pull request authors approve their own changes.
When a second reviewer is available, set `required_approving_review_count` to `1`
and `require_last_push_approval` to `true`. Stale approvals are already dismissed.
CodeRabbit continues reviewing pull requests, and its review threads must be resolved.

The two project workflows always start on pull requests. A change-detection job
filters each project's builds using the full pull request diff. Each required job
accepts a successful build (and backend image build) or an explicitly unchanged
project; detection errors, failed builds and cancelled builds fail the requirement.
This avoids GitHub's permanently pending checks when an entire required workflow
is skipped by a path filter. Push workflows retain their path filters and deploy
only from `main`.

Commit signatures are not required: local commit signing is not configured for
all contributors. Requiring them can also block squash merges of unsigned pull
request commits. Enable `required_signatures` only after signing is set up for
maintainers and dependency-update bots. The existing secret scanning and secret
push protection remain enabled.

To apply a reviewed change to the existing ruleset, first retrieve its ID:

```sh
gh api repos/vlad-dancea/vienna-pulse-v2/rulesets --jq '.[] | {id, name}'
gh api --method PUT repos/vlad-dancea/vienna-pulse-v2/rulesets/RULESET_ID \
  --input .github/rulesets/main.json
```

Read the ruleset back after updating it. Removing bypass actors also applies the
rules to the repository owner; administrative access can still edit the ruleset
for recovery. Changes to this JSON are not synchronized automatically.

See GitHub's [available rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)
and [required-check troubleshooting](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks).
