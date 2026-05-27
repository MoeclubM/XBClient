import { execSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

function gitText(args, required = true) {
  try {
    return execSync(['git', ...args].join(' '), {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim()
  } catch {
    if (required) throw new Error(`git ${args.join(' ')} failed`)
    return ''
  }
}

export function resolveAppVersion() {
  const gitCommitTimestamp = Number(gitText(['log', '-1', '--format=%ct']))
  const gitShortHash = gitText(['rev-parse', '--short=8', 'HEAD'])
  const gitExactTag = gitText(['describe', '--tags', '--exact-match', 'HEAD'], false)
  const gitLatestTag = gitText(['describe', '--tags', '--abbrev=0'], false)
  const gitCommitsSinceLatestTag =
    gitLatestTag.length > 0
      ? gitText(['rev-list', `${gitLatestTag}..HEAD`, '--count'], false) || '0'
      : ''

  const versionName = gitExactTag.replace(/^v/, '') || (
    gitLatestTag.length > 0
      ? `${gitLatestTag.replace(/^v/, '')}-beta.${gitCommitsSinceLatestTag}.${gitShortHash}`
      : `0.0.${gitCommitTimestamp}-${gitShortHash}`
  )

  const semverCore = /^(\d+)\.(\d+)\.(\d+)/.exec(versionName)
  const electronVersion = semverCore
    ? `${semverCore[1]}.${semverCore[2]}.${semverCore[3]}`
    : `0.0.${gitCommitTimestamp}`

  return { versionName, versionCode: gitCommitTimestamp, electronVersion }
}
