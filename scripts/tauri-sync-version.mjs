import { execFileSync, spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const tauriArgs = process.argv.slice(2)
const version = appVersionName()
const configArg = JSON.stringify({ version })
const separatorIndex = tauriArgs.indexOf('--')

if (separatorIndex >= 0) {
  tauriArgs.splice(separatorIndex, 0, '--config', configArg)
} else {
  tauriArgs.push('--config', configArg)
}

console.log(`Tauri version: ${version}`)

const requireFromApp = createRequire(resolve(process.cwd(), 'package.json'))
const tauriCli = requireFromApp.resolve('@tauri-apps/cli/tauri.js')
const result = spawnSync(process.execPath, [tauriCli, ...tauriArgs], {
  cwd: process.cwd(),
  stdio: 'inherit',
})

process.exit(result.status ?? 1)

function appVersionName() {
  const timestamp = gitText('log', '-1', '--format=%ct')
  const shortHash = gitText('rev-parse', '--short=8', 'HEAD')
  const exactTag = gitText('describe', '--tags', '--exact-match', 'HEAD', false)
  if (exactTag) return exactTag.replace(/^v/, '')

  const latestTag = gitText('describe', '--tags', '--abbrev=0', false)
  if (latestTag) {
    const count = gitText('rev-list', `${latestTag}..HEAD`, '--count', false) || '0'
    return `${latestTag.replace(/^v/, '')}-beta.${count}.${shortHash}`
  }

  return `0.0.${timestamp}-${shortHash}`
}

function gitText(...args) {
  let required = true
  if (typeof args.at(-1) === 'boolean') required = args.pop()
  try {
    return execFileSync('git', args, {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', required ? 'pipe' : 'ignore'],
    }).trim()
  } catch (error) {
    if (required) throw error
    return ''
  }
}
