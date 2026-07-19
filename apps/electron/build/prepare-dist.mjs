import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveAppVersion } from './resolve-version.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const electronDir = path.resolve(__dirname, '..')
const repoRoot = path.resolve(electronDir, '../..')
const configDir = path.join(electronDir, 'resources', 'config')
const buildConfigPath = path.join(configDir, 'build-config.json')

function requiredSecret(name) {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`缺少 GitHub Secret：${name}`)
  return value
}

const config = {
  appName: 'XBClient',
  defaultApiUrl: requiredSecret('XBCLIENT_DEFAULT_API_URL'),
  userAgent: requiredSecret('XBCLIENT_USER_AGENT'),
  oauthCallbackScheme: requiredSecret('XBCLIENT_OAUTH_CALLBACK_SCHEME'),
}

fs.mkdirSync(configDir, { recursive: true })
fs.writeFileSync(buildConfigPath, `${JSON.stringify(config, null, 2)}\n`)
console.log('wrote transient desktop build config from GitHub Actions Secrets')

const { electronVersion } = resolveAppVersion()
const ghRepo = process.env.GITHUB_REPOSITORY?.trim()
const websiteUrl = process.env.XBCLIENT_WEBSITE_URL?.trim() ?? ''
const homepage = websiteUrl.startsWith('http')
  ? websiteUrl
  : ghRepo
    ? `https://github.com/${ghRepo}`
    : 'https://github.com/MoeclubM/XBClient'

const pkgPath = path.join(electronDir, 'package.json')
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'))
pkg.version = electronVersion
pkg.homepage = homepage
fs.writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`)
console.log(`electron package version -> ${electronVersion}`)

const scheme = config.oauthCallbackScheme

let yml = fs.readFileSync(path.join(__dirname, 'electron-builder.yml'), 'utf8')
const linuxTargets = process.env.XBCLIENT_LINUX_TARGETS?.trim()
if (linuxTargets) {
  const list = linuxTargets
    .split(/[,\s]+/)
    .filter(Boolean)
    .map((t) => `    - ${t}`)
    .join('\n')
  yml = yml.replace(/^linux:\n  target:\n(?:    - .+\n)+/m, `linux:\n  target:\n${list}\n`)
}
if (scheme && !yml.includes('protocols:')) {
  yml += `\nprotocols:\n  - name: XBClient OAuth\n    schemes:\n      - ${scheme}\n`
}
if (ghRepo && !yml.includes('\npublish:')) {
  const [owner, repo] = ghRepo.split('/')
  if (owner && repo) {
    yml += `\npublish:\n  provider: github\n  owner: ${owner}\n  repo: ${repo}\n  vPrefixedTagName: true\n`
  }
}
fs.writeFileSync(path.join(__dirname, 'electron-builder.generated.yml'), yml)
