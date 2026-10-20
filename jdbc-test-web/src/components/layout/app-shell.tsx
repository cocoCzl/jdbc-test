import { GlassSurface } from "./GlassSurface";
import { LanguageSelector } from "./language-selector";
import { ThemeToggle } from "./theme-toggle";
import { useTranslation } from "react-i18next";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { Cable, Database, LayoutDashboard, Play, Terminal, ChevronDown } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { Button } from "@/components/ui/button";
const links = [
  { to: "/", key: "ui.dashboard", icon: LayoutDashboard },
  { to: "/drivers", key: "ui.drivers", icon: Database },
  { to: "/connections", key: "ui.connections", icon: Cable },
  { to: "/runs", key: "ui.runHistory", icon: Terminal },
  { to: "/runs/new", key: "ui.newRun", icon: Play },
];
export function AppShell() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const active = links.find(link => link.to === pathname) ?? links.find(link => link.to !== "/" && pathname.startsWith(link.to)) ?? links[0];
  return <div className="jdbc-app min-h-screen"><div className="jdbc-frame">
    <header className="jdbc-header">
      <Link to="/" className="jdbc-brand"><span className="jdbc-brand-icon"><Database className="size-5" /></span>jdbc-test</Link>
      <div className="flex items-center gap-5"><LanguageSelector /><ThemeToggle /></div>
    </header>
    <div className="jdbc-shell-body">
      <nav aria-label={t("ui.mainNavigation")} className="jdbc-navigation">
        <GlassSurface />
        <div className="jdbc-nav-desktop">
          {links.map(({to,key,icon: Icon}) => <NavLink key={to} to={to} end={to === "/" || (to === "/runs" && pathname.startsWith("/runs/new"))} className="jdbc-nav-link"><Icon className="size-4" />{t(key)}</NavLink>)}
        </div>
        <div className="jdbc-nav-mobile">
          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild><Button variant="ghost" aria-label={t("ui.openNavigation")}><active.icon className="size-4" />{t(active.key)}<ChevronDown /></Button></DropdownMenu.Trigger>
            <DropdownMenu.Portal><DropdownMenu.Content align="start" sideOffset={8} collisionPadding={8} className="jdbc-menu min-w-56">
              {links.map(({to,key,icon: Icon}) => <DropdownMenu.Item key={to} asChild><NavLink to={to} end={to === "/" || (to === "/runs" && pathname.startsWith("/runs/new"))} className="jdbc-menu-item"><span className="flex items-center gap-3"><Icon className="size-4" />{t(key)}</span></NavLink></DropdownMenu.Item>)}
            </DropdownMenu.Content></DropdownMenu.Portal>
          </DropdownMenu.Root>
        </div>
      </nav>
      <main className="page-enter min-w-0"><Outlet /></main>
    </div>
  </div></div>;
}
