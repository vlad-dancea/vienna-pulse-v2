import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';

// `build` records the commit it built from beside the served files, so the record travels in CI's
// artifact without being uploaded. `deploy` then refuses a build that production would not get from
// that commit: one that is stale, from another branch or from uncommitted changes.
const record = 'dist/vienna-pulse-frontend/source.json';
const rebuild = 'Run `mise run //vienna-pulse-frontend:build` and deploy again.';

/** Reads the checked-out commit and the frontend's uncommitted changes, untracked files included. */
function source() {
  const git = (...args) => execFileSync('git', args, { encoding: 'utf8' });
  // The root mise.toml pins the Node release that builds the frontend.
  const changes = git('status', '--porcelain', '--', '.', '../mise.toml').split('\n').filter(Boolean);
  return { commit: git('rev-parse', 'HEAD').trim(), changes };
}

/** Prints the reason and stops the task before anything is uploaded. */
function refuse(reason) {
  console.error(`Not deploying: ${reason}`);
  process.exit(1);
}

if (process.argv[2] === 'record') {
  await writeFile(record, `${JSON.stringify(source(), null, 2)}\n`);
} else if (process.argv[2] === 'verify') {
  const now = source();
  if (now.changes.length > 0) {
    refuse(
      `the frontend has uncommitted changes. Commit or stash them, then build and deploy again:\n${now.changes.join('\n')}`,
    );
  }
  let built;
  try {
    built = JSON.parse(await readFile(record, 'utf8'));
  } catch {
    refuse(`${record} is missing, so the build in dist/ comes from an unknown commit. ${rebuild}`);
  }
  if (built.changes.length > 0) {
    refuse(`dist/ was built with uncommitted changes:\n${built.changes.join('\n')}\nCommit them. ${rebuild}`);
  }
  if (built.commit !== now.commit) {
    refuse(`dist/ was built from ${built.commit.slice(0, 7)}, but HEAD is ${now.commit.slice(0, 7)}. ${rebuild}`);
  }
} else {
  throw new Error(`Unknown command ${process.argv[2]}, expected record or verify.`);
}
