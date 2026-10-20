import { NotFound } from "@/components/not-found";
import { createBrowserRouter, Outlet } from "react-router-dom";
import { AppShell } from "@/components/layout/app-shell";
import Dashboard from "@/features/dashboard/dashboard";
import Drivers from "@/features/drivers/drivers";
import DriverForm from "@/features/drivers/driver-form";
import DriverDetail from "@/features/drivers/driver-detail";
import Connections from "@/features/connections/connections";
import ConnectionForm from "@/features/connections/connection-form";
import Runs from "@/features/runs/runs";
import NewRun from "@/features/runs/new-run";
import RunDetail from "@/features/runs/run-detail";
export const routes = [
  {
    element: <AppShell />,
    children: [
      {
        element: (
          <Outlet />
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
