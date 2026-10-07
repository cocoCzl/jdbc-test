import { useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import {
  Cable,
  Database,
  LayoutDashboard,
  Menu,
  Play,
  Terminal,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
const links = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard },
  { to: "/drivers", label: "Drivers", icon: Database },
  { to: "/connections", label: "Connections", icon: Cable },
  { to: "/runs", label: "Run history", icon: Terminal },
  { to: "/runs/new", label: "New run", icon: Play },
];
export function AppShell() {
  const [drawer, setDrawer] = useState(false);
  const { pathname } = useLocation();
  const section = pathname.startsWith("/drivers")
    ? "Drivers"
    : pathname.startsWith("/connections")
      ? "Connections"
      : pathname.startsWith("/runs")
        ? "Test runs"
        : "Dashboard";
  return (
    <div className="min-h-screen">
      {drawer ? (
        <button
          aria-label="Close navigation overlay"
          onClick={() => setDrawer(false)}
          className="fixed inset-0 z-30 bg-slate-950/30 lg:hidden"
        />
      ) : null}
      <aside
        aria-label="Main navigation"
        className={cn(
          "fixed inset-y-0 left-0 z-40 w-60 flex-col border-r border-border bg-white px-4 py-5 transition-transform lg:flex",
          drawer ? "flex" : "hidden",
        )}
      >
        <div className="flex items-center justify-between">
          <Link
            to="/"
            onClick={() => setDrawer(false)}
            className="flex items-center gap-3"
          >
            <span className="flex size-9 items-center justify-center rounded-xl bg-primary text-white">
              <Database className="size-5" />
            </span>
            <span className="text-xl font-semibold tracking-tight">
              jdbc-test
            </span>
          </Link>
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Close navigation"
            onClick={() => setDrawer(false)}
          >
            <X />
          </Button>
        </div>
        <p className="eyebrow mb-3 mt-7 px-3">Workspace</p>
        <nav className="space-y-1">
          {links.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === "/" || to === "/runs"}
              onClick={() => setDrawer(false)}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-3 rounded-xl px-3 py-3 text-sm transition-colors",
                  isActive
                    ? "bg-accent font-semibold text-primary"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground",
                )
              }
            >
              <Icon className="size-[18px]" />
              {label}
            </NavLink>
          ))}
        </nav>
        <p className="mt-auto border-t pt-4 text-xs text-muted-foreground">Self-hosted workspace</p>
      </aside>
      <div className="lg:pl-60">
        <header className="flex h-16 items-center justify-between gap-4 border-b border-border bg-white/80 px-5 md:px-10">
          <div className="flex items-center gap-3">
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              aria-label="Open navigation"
              onClick={() => setDrawer(true)}
            >
              <Menu />
            </Button>
            <span className="text-sm text-muted-foreground">
              Workspace <span className="mx-3 text-border">/</span>
              <span className="font-medium text-foreground">{section}</span>
            </span>
          </div>

        </header>
        <main className="page-enter mx-auto max-w-[1560px] p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
