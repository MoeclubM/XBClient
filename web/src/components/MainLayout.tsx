import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useRef, useState } from 'react'
import type { CSSProperties, PointerEvent } from 'react'
import { useTranslation } from '../i18n'

export function MainLayout() {
  const t = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const hideNav = location.pathname.startsWith('/settings/licenses')
  const navRef = useRef<HTMLUListElement>(null)
  const dragRef = useRef({ active: false, offset: 0, lastX: 0, lastAt: 0 })
  const [drag, setDrag] = useState({ active: false, offset: 0, velocity: 0 })

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
  const selectedIndex = Math.max(0, TABS.findIndex((tab) => location.pathname.startsWith(tab.to)))
  const stretch = drag.active ? Math.min(Math.abs(drag.velocity) / 2600, 0.32) : 0
  const dropletAlpha = drag.active && Math.abs(drag.offset) > 2 ? Math.min(0.18 + stretch * 0.65, 0.38) : 0
  const dropMain = drag.velocity >= 0 ? '8px' : 'calc(100% / 4 - 40px)'
  const dropSmall = drag.velocity >= 0 ? '4px' : 'calc(100% / 4 - 24px)'

  function startNavDrag(event: PointerEvent<HTMLUListElement>) {
    const nav = navRef.current
    if (!nav) return
    const rect = nav.getBoundingClientRect()
    const itemWidth = rect.width / TABS.length
    const startX = event.clientX - rect.left
    const selectedStart = selectedIndex * itemWidth
    const active = startX >= selectedStart && startX <= selectedStart + itemWidth
    dragRef.current = { active, offset: 0, lastX: event.clientX, lastAt: Date.now() }
    if (active) {
      event.currentTarget.setPointerCapture(event.pointerId)
      setDrag({ active: true, offset: 0, velocity: 0 })
    }
  }

  function moveNavDrag(event: PointerEvent<HTMLUListElement>) {
    if (!dragRef.current.active || !navRef.current) return
    const rect = navRef.current.getBoundingClientRect()
    const itemWidth = rect.width / TABS.length
    const now = Date.now()
    const elapsed = Math.max(1, now - dragRef.current.lastAt)
    const delta = event.clientX - dragRef.current.lastX
    dragRef.current.lastX = event.clientX
    dragRef.current.lastAt = now
    dragRef.current.offset = Math.max(-selectedIndex * itemWidth, Math.min(dragRef.current.offset + delta, (TABS.length - 1 - selectedIndex) * itemWidth))
    setDrag({ active: true, offset: dragRef.current.offset, velocity: delta / elapsed * 1000 })
  }

  function endNavDrag(event: PointerEvent<HTMLUListElement>) {
    if (!dragRef.current.active || !navRef.current) return
    const itemWidth = navRef.current.getBoundingClientRect().width / TABS.length
    const targetIndex = Math.max(0, Math.min(TABS.length - 1, selectedIndex + Math.round(dragRef.current.offset / itemWidth)))
    dragRef.current = { active: false, offset: 0, lastX: 0, lastAt: 0 }
    event.currentTarget.releasePointerCapture(event.pointerId)
    setDrag({ active: false, offset: 0, velocity: 0 })
    if (targetIndex !== selectedIndex) navigate(TABS[targetIndex].to)
  }

  return (
    <div className="flex min-h-full flex-col bg-background-app text-on-background">
      <div className={`flex-1 overflow-y-auto ${hideNav ? '' : 'pb-[calc(7.25rem+env(safe-area-inset-bottom,0px))]'}`}>
        <Outlet />
      </div>
      {!hideNav && (
        <nav className="liquid-nav-shell fixed inset-x-0 bottom-0 z-40 pb-[calc(1rem+env(safe-area-inset-bottom,0px))]">
          <ul
            ref={navRef}
            className="liquid-nav mx-auto grid max-w-3xl touch-pan-y select-none grid-cols-4"
            style={{
              '--nav-index': selectedIndex,
              '--nav-offset': `${drag.active ? drag.offset : 0}px`,
              '--nav-stretch': stretch,
              '--nav-droplet-alpha': dropletAlpha,
              '--nav-drop-main': dropMain,
              '--nav-drop-small': dropSmall,
            } as CSSProperties}
            onPointerDown={startNavDrag}
            onPointerMove={moveNavDrag}
            onPointerUp={endNavDrag}
            onPointerCancel={endNavDrag}
          >
            <span className="liquid-nav__drop liquid-nav__drop--main" />
            <span className="liquid-nav__drop liquid-nav__drop--small" />
            <span className="liquid-nav__pill" />
            {TABS.map((tab) => (
              <li key={tab.to} className="relative z-10 flex justify-center">
                <NavLink
                  to={tab.to}
                  className="liquid-nav__item"
                >
                  {({ isActive }) => (
                    <div className={isActive ? 'flex flex-col items-center justify-center gap-1 text-primary' : 'flex flex-col items-center justify-center gap-1 text-[var(--on-surface-variant)]'}>
                      <div className={isActive ? 'liquid-nav__icon liquid-nav__icon--active' : 'liquid-nav__icon'}>
                        {tab.icon}
                      </div>
                      <span className={isActive ? 'text-[11px] font-bold' : 'text-[11px] font-semibold'}>
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
