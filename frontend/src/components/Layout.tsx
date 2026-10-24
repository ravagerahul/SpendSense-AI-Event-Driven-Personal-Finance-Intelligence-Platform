import { NavLink, Outlet } from 'react-router-dom';

const ROUTES = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/transactions', label: 'Transactions' },
  { to: '/chat', label: 'Chat' },
  { to: '/review', label: 'Review' },
  { to: '/anomalies', label: 'Anomalies' },
  { to: '/insights', label: 'Insights' },
];

export function Layout() {
  return (
    <div className="app">
      <nav className="sidebar" aria-label="Main">
        <div className="sidebar__brand">
          SpendSense
          <span>Personal finance intelligence</span>
        </div>

        <div className="nav">
          {ROUTES.map((route) => (
            <NavLink
              key={route.to}
              to={route.to}
              // Without `end`, "/" matches every path and the Dashboard link stays highlighted
              // on every page.
              end={route.end}
              className={({ isActive }) => `nav__link${isActive ? ' nav__link--active' : ''}`}
            >
              {route.label}
            </NavLink>
          ))}
        </div>

        <div className="sidebar__footer">Local development</div>
      </nav>

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}

/** The heading block every page shares, so the type scale is applied in one place. */
export function PageHeader({ title, description }: { title: string; description?: string }) {
  return (
    <header className="page__header">
      <h1>{title}</h1>
      {description && <p className="page__description">{description}</p>}
    </header>
  );
}
