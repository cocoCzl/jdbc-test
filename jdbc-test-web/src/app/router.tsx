import { NotFound } from "@/components/not-found";
import { lazy, Suspense } from "react";
import { createBrowserRouter, Outlet } from "react-router-dom";
import { AppShell } from "@/components/layout/app-shell";
import { Loading } from "@/components/common";
const Dashboard = lazy(() => import("@/features/dashboard/dashboard"));
const Drivers = lazy(() => import("@/features/drivers/drivers"));
const DriverForm = lazy(() => import("@/features/drivers/driver-form"));
const DriverDetail = lazy(() => import("@/features/drivers/driver-detail"));
const Connections = lazy(() => import("@/features/connections/connections"));
const ConnectionForm = lazy(
  () => import("@/features/connections/connection-form"),
);
const Runs = lazy(() => import("@/features/runs/runs"));
const NewRun = lazy(() => import("@/features/runs/new-run"));
const RunDetail = lazy(() => import("@/features/runs/run-detail"));
export const routes = [
  {
    element: <AppShell />,
    children: [
      {
        element: (
          <Suspense fallback={<Loading />}>
            <Outlet />
          </Suspense>
        ),
        children: [
          { index: true, element: <Dashboard /> },
          { path: "drivers", element: <Drivers /> },
          { path: "drivers/new", element: <DriverForm /> },
          { path: "drivers/:id", element: <DriverDetail /> },
          { path: "connections", element: <Connections /> },
          { path: "connections/new", element: <ConnectionForm /> },
          { path: "connections/:id", element: <ConnectionForm /> },
          { path: "runs", element: <Runs /> },
          { path: "runs/new", element: <NewRun /> },
          { path: "runs/:id", element: <RunDetail /> },
          {
            path: "*",
            element: <NotFound />,
          },
        ],
      },
    ],
  },
];
export const router = createBrowserRouter(routes);
