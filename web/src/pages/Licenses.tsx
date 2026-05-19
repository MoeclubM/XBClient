import { openExternal } from '../api/system'
import { useTranslation } from '../i18n'

const ITEMS = [
  ['XBClient', 'https://github.com/MoeclubM/XBClient'],
  ['Aerion', 'https://github.com/MoeclubM/Aerion'],
  ['Tauri', 'https://tauri.app'],
  ['React', 'https://react.dev'],
  ['Zustand', 'https://github.com/pmndrs/zustand'],
]

export function Licenses() {
  const t = useTranslation()

  return (
    <main className="mx-auto max-w-3xl space-y-5 p-6 pb-24">
      <header className="border-b border-outline-variant/30 pb-3 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-primary">{t('licenses')}</h1>
          <p className="mt-1 text-xs text-on-surface-variant font-medium">
            Tauri 多平台版依赖的主要项目 / Core open source projects dependencies.
          </p>
        </div>
        <a
          className="inline-flex items-center gap-1 rounded-xl bg-primary/10 px-3 py-1.5 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all"
          href="#/settings"
        >
          ← {t('nav_settings')}
        </a>
      </header>

      <ul className="space-y-3">
        {ITEMS.map(([name, url]) => (
          <li
            key={name}
            className="flex items-center justify-between rounded-2xl bg-surface-low p-4 shadow-sm border border-outline-variant/40 hover:border-primary/40 transition-all duration-200"
          >
            <span className="font-bold text-sm tracking-wide">{name}</span>
            <button
              onClick={() => void openExternal(url)}
              className="rounded-xl bg-primary/10 px-4 py-1.5 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer"
            >
              🌐 Open
            </button>
          </li>
        ))}
      </ul>
    </main>
  )
}
