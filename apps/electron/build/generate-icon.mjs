import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import toIco from 'to-ico'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../../..')
const size = 512

const sourceCandidates = [
  path.join(repoRoot, 'web/public/logo.png'),
  path.join(repoRoot, 'app/src/main/res/drawable-nodpi/ic_launcher.png'),
]

const sourcePath = sourceCandidates.find((file) => fs.existsSync(file))
if (!sourcePath) {
  throw new Error(`找不到应用图标源文件：${sourceCandidates.join(' 或 ')}`)
}

function sample(src, x, y, channel) {
  const px = Math.max(0, Math.min(src.width - 1, x))
  const py = Math.max(0, Math.min(src.height - 1, y))
  return src.data[((src.width * py + px) << 2) + channel]
}

function resizePng(src, targetSize) {
  const dst = new PNG({ width: targetSize, height: targetSize })
  for (let y = 0; y < targetSize; y++) {
    for (let x = 0; x < targetSize; x++) {
      const u = ((x + 0.5) / targetSize) * src.width - 0.5
      const v = ((y + 0.5) / targetSize) * src.height - 0.5
      const x0 = Math.floor(u)
      const y0 = Math.floor(v)
      const x1 = Math.min(x0 + 1, src.width - 1)
      const y1 = Math.min(y0 + 1, src.height - 1)
      const tx = u - x0
      const ty = v - y0
      const idx = (targetSize * y + x) << 2
      for (let c = 0; c < 4; c++) {
        const p00 = sample(src, x0, y0, c)
        const p10 = sample(src, x1, y0, c)
        const p01 = sample(src, x0, y1, c)
        const p11 = sample(src, x1, y1, c)
        dst.data[idx + c] = Math.round(
          p00 * (1 - tx) * (1 - ty) + p10 * tx * (1 - ty) + p01 * (1 - tx) * ty + p11 * tx * ty,
        )
      }
    }
  }
  return dst
}

const src = PNG.sync.read(fs.readFileSync(sourcePath))
const png = resizePng(src, size)

const pngPath = path.join(__dirname, 'icon.png')
const icoPath = path.join(__dirname, 'icon.ico')

fs.writeFileSync(pngPath, PNG.sync.write(png))
const icoBuffer = await toIco([fs.readFileSync(pngPath)], { resize: true, sizes: [16, 24, 32, 48, 64, 128, 256] })
fs.writeFileSync(icoPath, icoBuffer)

console.log(`wrote apps/electron/build/icon.png and icon.ico from ${path.relative(repoRoot, sourcePath)}`)
