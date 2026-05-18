import { NavLink, Outlet, useLocation } from 'react-router-dom'

const TABS: { to: string; label: string }[] = [
  { to: '/home', label: '节点' },
  { to: '/plans', label: '套餐' },
  { to: '/profile', label: '个人' },
  { to: '/settings', label: '设置' },
]

export function MainLayout() {
  const location = useLocation()
  const hideNav = location.pathname.startsWith('/settings/licenses')
  return (
    <div className="flex min-h-full flex-col">
      <div className={`flex-1 overflow-y-auto ${hideNav ? '' : 'pb-20'}`}>
        <Outlet />
      </div>
      {!hideNav && (
        <nav className="fixed inset-x-0 bottom-0 z-10 border-t border-white/10 bg-slate-950/85 backdrop-blur">
          <ul className="mx-auto flex max-w-3xl items-stretch">
            {TABS.map((tab) => (
              <li key={tab.to} className="flex-1">
                <NavLink
                  to={tab.to}
                  className={({ isActive }) =>
                    `flex h-14 items-center justify-center text-sm transition ${
                      isActive ? 'text-sky-400' : 'text-slate-400 hover:text-slate-200'
                    }`
                  }
                >
                  {tab.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  )
}
