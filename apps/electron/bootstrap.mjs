// Wayland-first: must run before Electron loads (static import in main.mjs is too late).
if (process.platform === 'linux' && !process.env.ELECTRON_OZONE_PLATFORM_HINT) {
  process.env.ELECTRON_OZONE_PLATFORM_HINT = 'auto'
}
await import('./main.mjs')
