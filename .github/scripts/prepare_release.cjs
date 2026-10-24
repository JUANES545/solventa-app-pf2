'use strict';

const fs = require('node:fs');
const path = require('node:path');
const childProcess = require('node:child_process');
const { versionFromText, versionBaseline, incrementVersion, assertIncrease } = require('./release_android.cjs');

function prepareRelease({ level, releaseId, directory = process.cwd() }) {
  if (typeof releaseId !== 'string' || !/^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}-[1-9][0-9]*$/.test(releaseId)) {
    throw new Error('Indicar RELEASE_ID con formato AAAA-MM-DD-N');
  }
  const properties = path.join(directory, 'version.properties');
  let text = fs.readFileSync(properties, 'utf8');
  const previous = versionFromText(text);
  const tags = childProcess.execFileSync('git', ['tag', '--list'], { cwd: directory, encoding: 'utf8' }).trim().split(/\r?\n/);
  const baseline = versionBaseline(previous.name, tags);
  const values = {
    VERSION_NAME: incrementVersion(baseline, level),
    VERSION_CODE: previous.code + 1,
    RELEASE_ID: releaseId,
    RELEASE_LEVEL: level,
  };
  for (const [key, value] of Object.entries(values)) {
    const pattern = new RegExp(`^[ \\t]*${key}[ \\t]*=[^\\r\\n]*`, 'm');
    text = pattern.test(text) ? text.replace(pattern, `${key}=${value}`) :
      text + (text.endsWith('\n') ? '' : '\n') + `${key}=${value}\n`;
  }
  const next = versionFromText(text);
  assertIncrease(next, previous);
  fs.writeFileSync(properties, text);
  return { ...next, baseline };
}

if (require.main === module) {
  try {
    if (process.argv.length !== 4) throw new Error('Uso: node .github/scripts/prepare_release.cjs <patch|minor|major> <AAAA-MM-DD-N>');
    const next = prepareRelease({ level: process.argv[2], releaseId: process.argv[3] });
    console.log(`${next.baseline} -> ${next.name} (${next.level}); VERSION_CODE=${next.code}; RELEASE_ID=${next.releaseId}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = { prepareRelease };
