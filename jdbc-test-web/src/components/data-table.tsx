import { t } from "i18next";
import { useTranslation } from "react-i18next";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { plural } from "@/lib/format";
import { Button } from "./ui/button";
import { ChevronLeft, ChevronRight } from "lucide-react";
export function DataTable<T>({
  rows,
  columns,
  empty,
  className,
}: {
  rows: T[];
  columns: ColumnDef<T>[];
  empty: React.ReactNode;
  className?: string;
}) {
  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });
  if (!rows.length) return <>{empty}</>;
  return (
    <div className="max-w-full overflow-x-auto">
      <table className={`jdbc-table w-full text-left ${className ?? ""}`}>
        <thead className="border-b">
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id}>
              {group.headers.map((header) => (
                <th
                  key={header.id}
                  scope="col"
                  className="whitespace-nowrap"
                >
                  {flexRender(
                    header.column.columnDef.header,
                    header.getContext(),
                  )}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody className="divide-y divide-border">
          {table.getRowModel().rows.map((row) => (
            <tr key={row.id} className="">
              {row.getVisibleCells().map((cell) => (
                <td key={cell.id} className="[overflow-wrap:anywhere]">
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}) {
  useTranslation();
  return (
    <div className="flex items-center justify-between gap-4 border-t px-4 py-3 text-xs text-muted-foreground">
      <span>
        {plural(totalElements, "record")} · {t("pagination.page", {page: page + 1, pages: Math.max(1, totalPages)})}
      </span>
      {totalPages > 1 ? <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          aria-label={t("ui.previousPage")}
          disabled={page === 0}
          onClick={() => onPage(page - 1)}
        >
          <ChevronLeft />
          {t("ui.previous")}</Button>
        <Button
          variant="outline"
          size="sm"
          aria-label={t("ui.nextPage")}
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          {t("ui.next")}<ChevronRight />
        </Button>
      </div> : null}
    </div>
  );
}
