import { readFile, writeFile } from 'node:fs/promises';
import { directory, hashedFiles } from './build.mjs';

// Header rules match the URL, and the SPA fallback answers a missing /chunk-*.js with index.html,
// so a pattern such as /*.js would cache that fallback as immutable. Naming each built file
// keeps an old chunk that an open tab still requests after a deploy from caching the shell.
const rules = (await hashedFiles()).map((file) => `/${file}\n  Cache-Control: public, max-age=31536000, immutable\n`);
const source = await readFile('public/_headers', 'utf8');
await writeFile(
  `${directory}/_headers`,
  `${source}\n# Built JavaScript and CSS carry a content hash in their names, so a changed file gets a new URL.\n${rules.join('')}`,
);
