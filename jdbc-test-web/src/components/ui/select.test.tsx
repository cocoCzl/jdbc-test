import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Select } from "./select";
import { Dialog } from "./dialog";
import { chooseOption } from "@/test/select";

function Example({onChange = () => {}}: {onChange?: (value: string) => void}) {
  const [value, setValue] = useState("");
  return <Select aria-label="Status" value={value} onValueChange={next => {setValue(next); onChange(next);}}>
    <option value="">All statuses</option><option value="FAILED" disabled>Disabled</option><option value="SUCCEEDED">Run succeeded</option>
  </Select>;
}
describe("Unified Select", () => {
  it("uses a visible custom listbox and preserves original empty and enum values", async () => {
    const user = userEvent.setup(); const change = vi.fn(); render(<Example onChange={change} />);
    const trigger = screen.getByRole("combobox", {name:"Status"});
    await chooseOption(user, trigger, "SUCCEEDED");
    expect(trigger).toHaveTextContent("Run succeeded"); expect(change).toHaveBeenLastCalledWith("SUCCEEDED");
    await chooseOption(user, trigger, ""); expect(change).toHaveBeenLastCalledWith("");
    expect(trigger).toHaveAttribute("data-value", ""); expect(change).toHaveBeenCalledTimes(2);
  });
  it("supports keyboard selection, skips disabled options and returns focus after Escape", async () => {
    const user = userEvent.setup(); const change = vi.fn(); render(<Example onChange={change} />);
    const trigger = screen.getByRole("combobox"); trigger.focus(); await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("listbox")).toBeVisible();
    expect(screen.getByRole("option", {name:"Disabled"})).toHaveAttribute("aria-disabled","true");
    await user.keyboard("{End}{Enter}"); expect(change).toHaveBeenLastCalledWith("SUCCEEDED");
    await user.click(trigger); await user.keyboard("{Escape}"); expect(trigger).toHaveFocus(); expect(screen.queryByRole("listbox")).toBeNull();
  });
  it("preserves native required validation without leaking internal placeholder values", () => {
    render(<form><Select name="driver" aria-label="Driver" required value="" onValueChange={() => {}}><option value="">Choose driver</option><option value="id-1">Driver</option></Select></form>);
    expect(document.querySelector("form")!.checkValidity()).toBe(false);
  });
  it("closes a nested menu before its dialog and retains focus", async () => {
    const user = userEvent.setup(); const close = vi.fn(); render(<Dialog open onOpenChange={close} title="Settings fixture" description="Only a test"><Example /></Dialog>);
    const trigger = screen.getByRole("combobox"); await user.click(trigger); await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).toBeNull(); expect(close).not.toHaveBeenCalled(); expect(trigger).toHaveFocus();
  });
});
