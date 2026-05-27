import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import toIco from 'to-ico'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const size = 512

const png = new PNG({ width: size, height: size })
for (let y = 0; y < size; y++) {
  for (let x = 0; x < size; x++) {
    const idx = (size * y + x) << 2
    const t = y / size
    png.data[idx] = Math.round(0x38 + (0x0e - 0x38) * t)
    png.data[idx + 1] = Math.round(0xbd + (0x74 - 0xbd) * t)
    png.data[idx + 2] = Math.round(0xf8 + (0xbe - 0xf8) * t)
    png.data[idx + 3] = 255
  }
}

const pngPath = path.join(__dirname, 'icon.png')
const icoPath = path.join(__dirname, 'icon.ico')

await new Promise((resolve, reject) => {
  png
    .pack()
    .pipe(fs.createWriteStream(pngPath))
    .on('finish', resolve)
    .on('error', reject)
})

const pngBuffer = fs.readFileSync(pngPath)
const icoBuffer = await toIco([pngBuffer], { resize: true, sizes: [16, 24, 32, 48, 64, 128, 256] })
fs.writeFileSync(icoPath, icoBuffer)

console.log('wrote apps/electron/build/icon.png and icon.ico')
