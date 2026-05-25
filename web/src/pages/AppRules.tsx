import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { androidListInstalledApps, type InstalledAppItem } from '../api/system'
import { publicErrorText } from '../format'
import { useTranslation } from '../i18n'
import { useAppStore, type AppSettings } from '../store'
import { saveSettings } from '../store/persist'

function packageList(value: string): string[] {
  return value.split(/[,;\s]+/).filter(Boolean)
}

export function AppRules() {
  const t = useTranslation()
  const { settings, capabilities, setSettings } = useAppStore()
  const [apps, setApps] = useState<InstalledAppItem[]>([])
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')
  const packages = useMemo(
    () => packageList(settings.appRuleMode === 'allow' ? settings.allowedApps : settings.excludedApps),
    [settings.allowedApps, settings.appRuleMode, settings.excludedApps],
  )
  const packageSet = useMemo(() => new Set(packages), [packages])
  const filteredApps = useMemo(() => {
    const text = query.trim().toLowerCase()
    if (!text) return apps
    return apps.filter((app) =>
      app.label.toLowerCase().includes(text) || app.packageName.toLowerCase().includes(text),
    )
  }, [apps, query])

  useEffect(() => {
    if (!capabilities?.vpn) return
    let cancelled = false
    androidListInstalledApps()
      .then((result) => {
        if (!cancelled) setApps(result.apps)
      })
      .catch((err) => {
        if (!cancelled) setError(publicErrorText(err))
      })
    return () => {
      cancelled = true
    }
  }, [capabilities?.vpn])

  async function persist(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((err) => setError(`Save failed: ${publicErrorText(err)}`))
  }

  async function switchMode(mode: AppSettings['appRuleMode']) {
    const value = packages.join(' ')
    await persist(
      mode === 'allow'
        ? { appRuleMode: mode, allowedApps: value, excludedApps: '' }
        : { appRuleMode: mode, excludedApps: value, allowedApps: '' },
    )
  }

  async function setAppSelected(packageName: string, selected: boolean) {
    const next = selected
      ? [...packages.filter((value) => value !== packageName), packageName]
      : packages.filter((value) => value !== packageName)
    await persist(
      settings.appRuleMode === 'allow'
        ? { allowedApps: next.join(' '), excludedApps: '' }
        : { excludedApps: next.join(' '), allowedApps: '' },
    )
  }

  if (!capabilities?.vpn) {
    return (
      <main className="md3-screen space-y-5">
        <header className="md3-page-header">
          <span className="md3-page-rail" />
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('page_app_rules_title')}</h1>
            <p className="mt-1 text-sm text-on-surface-variant">{t('vpn_app_rules_android_only')}</p>
          </div>
        </header>
        <Link to="/settings" className="md3-button md3-button-outlined w-full">
          {t('common_back_settings')}
        </Link>
      </main>
    )
  }

  return (
    <main className="md3-screen space-y-5">
      <header className="md3-page-header">
        <span className="md3-page-rail" />
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('page_app_rules_title')}</h1>
          <p className="mt-1 text-sm text-on-surface-variant">{t('page_app_rules_subtitle')}</p>
        </div>
      </header>

      {error && (
        <p className="md3-alert md3-alert-error break-words">
          {error}
        </p>
      )}

      <section className="md3-card-low space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <button
            type="button"
            className={settings.appRuleMode === 'exclude' ? 'md3-button md3-button-filled' : 'md3-button md3-button-outlined'}
            onClick={() => void switchMode('exclude')}
          >
            {t('mode_exclude')}
          </button>
          <button
            type="button"
            className={settings.appRuleMode === 'allow' ? 'md3-button md3-button-filled' : 'md3-button md3-button-outlined'}
            onClick={() => void switchMode('allow')}
          >
            {t('mode_allow')}
          </button>
        </div>

        <input
          className="md3-field"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t('search_app_label')}
        />

        <p className="text-sm text-on-surface-variant">
          {t('app_rules_selected_count').replace('{count}', String(packages.length))}
        </p>

        <div className="grid grid-cols-2 gap-3">
          <Link to="/settings" className="md3-button md3-button-outlined text-center">
            {t('common_back_settings')}
          </Link>
          <button
            type="button"
            className="md3-button md3-button-tonal"
            onClick={() => void persist(settings.appRuleMode === 'allow' ? { allowedApps: '' } : { excludedApps: '' })}
          >
            {t('common_clear_selection')}
          </button>
        </div>
      </section>

      <section className="md3-card-low divide-y divide-outline-variant/20 p-0">
        {filteredApps.map((app) => {
          const selected = packageSet.has(app.packageName)
          return (
            <label key={app.packageName} className="flex cursor-pointer items-center gap-3 px-4 py-3">
              <input
                type="checkbox"
                className="size-4 shrink-0 accent-primary"
                checked={selected}
                onChange={(event) => void setAppSelected(app.packageName, event.target.checked)}
              />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold text-on-background">{app.label}</span>
                <span className="block truncate font-mono text-xs text-on-surface-variant">{app.packageName}</span>
              </span>
            </label>
          )
        })}
      </section>
    </main>
  )
}
