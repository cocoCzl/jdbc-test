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
}: {
  rows: T[];
  columns: ColumnDef<T>[];
  empty: React.ReactNode;
}) {
  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });
  if (!rows.length) return <>{empty}</>;
  return (
    <div className="max-w-full overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id}>
              {group.headers.map((header) => (
                <th
                  key={header.id}
                  scope="col"
                  className="whitespace-nowrap px-4 py-3 font-medium"
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
            <tr key={row.id} className="hover:bg-muted/40">
              {row.getVisibleCells().map((cell) => (
                <td key={cell.id} className="px-4 py-3 [overflow-wrap:anywhere]">
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
  return (
    <div className="flex items-center justify-between gap-4 border-t px-4 py-3 text-xs text-muted-foreground">
      <span>
        {plural(totalElements, "record")} · Page {page + 1} of {Math.max(1, totalPages)}
      </span>
      {totalPages > 1 ? <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => onPage(page - 1)}
        >
          <ChevronLeft />
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          aria-label="Next page"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          Next
          <ChevronRight />
        </Button>
      </div> : null}
    </div>
  );
}
