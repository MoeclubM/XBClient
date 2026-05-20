import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from '../i18n'

export function MainLayout() {
  const t = useTranslation()
  const location = useLocation()
  const hideNav = location.pathname.startsWith('/settings/licenses')

  const TABS = [
    {
      to: '/home',
      label: t('nav_nodes'),
      icon: (
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect>
          <rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect>
          <line x1="6" y1="6" x2="6.01" y2="6"></line>
          <line x1="6" y1="18" x2="6.01" y2="18"></line>
        </svg>
      )
    },
    {
      to: '/plans',
      label: t('nav_plans'),
      icon: (
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="5" width="20" height="14" rx="2" ry="2"></rect>
          <line x1="2" y1="10" x2="22" y2="10"></line>
        </svg>
      )
    },
    {
      to: '/profile',
      label: t('nav_profile'),
      icon: (
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
          <circle cx="12" cy="7" r="4"></circle>
        </svg>
      )
    },
    {
      to: '/settings',
      label: t('nav_settings'),
      icon: (
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="3"></circle>
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
        </svg>
      )
    },
  ]

  return (
    <div className="flex min-h-full flex-col bg-background-app text-on-background">
      <div className={`flex-1 overflow-y-auto ${hideNav ? '' : 'pb-[calc(5rem+env(safe-area-inset-bottom,0px))]'}`}>
        <Outlet />
      </div>
      {!hideNav && (
        <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-outline-variant/40 bg-[var(--nav-bg)] pb-[env(safe-area-inset-bottom,0px)]">
          <ul className="mx-auto flex h-14 max-w-3xl items-center justify-around px-3">
            {TABS.map((tab) => (
              <li key={tab.to} className="flex-1 flex justify-center">
                <NavLink
                  to={tab.to}
                  className="w-full"
                >
                  {({ isActive }) => (
                    <div className={isActive ? 'flex flex-col items-center justify-center gap-1 text-primary' : 'flex flex-col items-center justify-center gap-1 text-[var(--on-surface-variant)]'}>
                      <div className="flex h-6 items-center justify-center">
                        {tab.icon}
                      </div>
                      <span className="text-[10px] font-medium">
                        {tab.label}
                      </span>
                    </div>
                  )}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  )
}
