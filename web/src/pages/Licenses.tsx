import { openExternal } from '../api/system'

const ITEMS = [
  ['XBClient', 'https://github.com/MoeclubM/XBClient'],
  ['Aerion', 'https://github.com/MoeclubM/Aerion'],
  ['Tauri', 'https://tauri.app'],
  ['React', 'https://react.dev'],
  ['Zustand', 'https://github.com/pmndrs/zustand'],
]

export function Licenses() {
  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold">开源许可</h1>
        <p className="text-xs text-slate-400">Tauri 多平台版依赖的主要项目。</p>
      </header>
      <ul className="space-y-2">
        {ITEMS.map(([name, url]) => (
          <li key={name} className="flex items-center justify-between rounded-xl bg-slate-900/60 p-4 ring-1 ring-white/10">
            <span>{name}</span>
            <button
              onClick={() => void openExternal(url)}
              className="rounded-lg bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600"
            >
              打开
            </button>
          </li>
        ))}
      </ul>
      <a className="text-sm text-sky-300 hover:text-sky-200" href="#/settings">
        返回设置
      </a>
    </main>
  )
}
