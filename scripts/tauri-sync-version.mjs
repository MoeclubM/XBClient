import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const tauriArgs = process.argv.slice(2)
const version = appVersionName()
const localProperties = readLocalProperties(resolve(repoRoot, 'local.properties'))
const appName = configuredValue('XBCLIENT_APP_NAME', 'xbclient.appName', 'XBClient')
const applicationId = configuredValue('XBCLIENT_APPLICATION_ID', 'xbclient.applicationId', 'moe.telecom.xbclient')
const defaultApiUrl = requiredConfiguredValue('XBCLIENT_DEFAULT_API_URL', 'xbclient.defaultApiUrl')
const userAgent = requiredConfiguredValue('XBCLIENT_USER_AGENT', 'xbclient.userAgent')
const oauthCallbackScheme = requiredConfiguredValue('XBCLIENT_OAUTH_CALLBACK_SCHEME', 'xbclient.oauthCallbackScheme')
const configArg = JSON.stringify({
  version,
  productName: appName,
})
const separatorIndex = tauriArgs.indexOf('--')

if (separatorIndex >= 0) {
  tauriArgs.splice(separatorIndex, 0, '--config', configArg)
} else {
  tauriArgs.push('--config', configArg)
}

console.log(`Tauri version: ${version}`)
console.log(`Tauri productName: ${appName}`)

const requireFromApp = createRequire(resolve(process.cwd(), 'package.json'))
const tauriCli = requireFromApp.resolve('@tauri-apps/cli/tauri.js')
const result = spawnSync(process.execPath, [tauriCli, ...tauriArgs], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    XBCLIENT_APP_NAME: appName,
    XBCLIENT_APPLICATION_ID: applicationId,
    XBCLIENT_DEFAULT_API_URL: defaultApiUrl,
    XBCLIENT_USER_AGENT: userAgent,
    XBCLIENT_OAUTH_CALLBACK_SCHEME: oauthCallbackScheme,
  },
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

function requiredConfiguredValue(environmentVariable, propertyName) {
  const value = configuredValue(environmentVariable, propertyName, '')
  if (!value) {
    throw new Error(`${environmentVariable}, -P${propertyName} or local.properties ${propertyName} is required`)
  }
  return value
}

function configuredValue(environmentVariable, propertyName, fallback) {
  return (
    process.env[environmentVariable] ||
    localProperties[propertyName] ||
    localProperties[environmentVariable] ||
    fallback
  ).trim()
}

function readLocalProperties(file) {
  if (!existsSync(file)) return {}
  const entries = {}
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const text = line.trim()
    if (!text || text.startsWith('#') || text.startsWith('!')) continue
    const index = text.search(/[:=]/)
    if (index <= 0) continue
    entries[text.slice(0, index).trim()] = text.slice(index + 1).trim()
  }
  return entries
}
