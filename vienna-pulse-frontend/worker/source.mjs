import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { directory } from './build.mjs';

// `build` records the commit it built from beside the served files, so the record travels in CI's
// artifact without being uploaded. `deploy` then refuses a build that production would not get from
// that commit: one that is stale, from another branch or from uncommitted changes.
const record = join(dirname(directory), 'source.json');
// Angular empties the output folder when it starts, so the state before the build waits in its cache.
const started = '.angular/source.json';
const rebuild = 'Run `mise run //vienna-pulse-frontend:build` and deploy again.';

/** Reads the checked-out commit and the frontend's uncommitted changes, untracked files included. */
function source() {
  const git = (...args) => execFileSync('git', args, { encoding: 'utf8' });
  // The root mise.toml pins the Node release that builds the frontend.
  const changes = git('status', '--porcelain', '--untracked-files=all', '--', '.', '../mise.toml')
    .split('\n')
    .filter(Boolean);
  return { commit: git('rev-parse', 'HEAD').trim(), changes };
}

/** Prints the reason and stops the task before anything is uploaded. */
function refuse(reason) {
  console.error(`Not deploying: ${reason}`);
  process.exit(1);
}

if (process.argv[2] === 'start') {
  await mkdir('.angular', { recursive: true });
  await writeFile(started, JSON.stringify(source()));
} else if (process.argv[2] === 'record') {
  const before = JSON.parse(await readFile(started, 'utf8'));
  const after = source();
  // A checkout or an edit during the build leaves files from both states, which no commit describes.
  if (JSON.stringify(before) !== JSON.stringify(after)) {
    after.changes.push(`the sources changed during the build, which started at ${before.commit.slice(0, 7)}`);
  }
  await writeFile(record, `${JSON.stringify(after, null, 2)}\n`);
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
  throw new Error(`Unknown command ${process.argv[2]}, expected start, record or verify.`);
}
