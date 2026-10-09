// Test-only entry, served by Vite dev; not included in production build or router.
import "../../src/i18n";
import "../../src/index.css";
import "../../src/styles/foundation.scss";
import { useState } from "react";
import { Toaster, toast } from "sonner";
import { Dialog } from "../../src/components/ui/dialog";
import { Select } from "../../src/components/ui/select";
import { Button } from "../../src/components/ui/button";
import { Tooltip } from "../../src/components/ui/tooltip";
import { Card } from "../../src/components/ui/card";
import { EmptyState, ErrorState, Loading } from "../../src/components/common";
export default function Fixture() {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("option-0");
  const [submits, setSubmits] = useState(0);
  return <main className="mx-auto max-w-4xl space-y-5 p-4">
    <h1>TEST FIXTURE ONLY — shared primitives; no database</h1>
    <Card title="Global interaction states">
      <div className="flex flex-wrap gap-3">
        <Button type="button" onClick={() => setOpen(true)}>Open modal</Button>
        <Tooltip label="Keyboard and pointer tooltip"><Button type="button" variant="outline">Help fixture</Button></Tooltip>
        <Button type="button" disabled>Disabled fixture</Button>
        <Button type="button" disabled aria-busy="true">Loading fixture</Button>
        <Button type="button" variant="outline" onClick={() => toast.error("Fixture error toast")}>Show toast</Button>
      </div>
      <p className="mt-4">Submit count: {submits}</p>
    </Card>
    <EmptyState title="Fixture empty" description="No simulated executions" />
    <ErrorState error={new Error("Fixture unavailable")} retry={() => toast.info("Fixture retry only")} />
    <Loading label="Fixture loading" />
    <Dialog open={open} onOpenChange={setOpen} title="Modal fixture" description="Test-only options; no API requests">
      <form onSubmit={event => {event.preventDefault(); setSubmits(count => count+1);}}>
        <label htmlFor="fixture-options">Nested selection</label>
        <Select id="fixture-options" className="mt-3" value={value} onValueChange={setValue}>
          {Array.from({length:50},(_,i) => <option key={i} value={`option-${i}`} disabled={i === 1}>{i === 1 ? "Disabled option" : `Long option ${i} — ${"JDBC choice ".repeat(4)}`}</option>)}
        </Select>
        <p className="mt-4">Selected ID: {value}</p>
        <Button type="submit" variant="outline" className="mt-4">Fixture submit</Button>
      </form>
    </Dialog>
    <Toaster richColors />
  </main>;
}
