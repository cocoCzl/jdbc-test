import { screen } from "@testing-library/react";
import type userEvent from "@testing-library/user-event";
/** Exercise the visible portalled UI, never the hidden form compatibility select. */
export async function chooseOption(user: ReturnType<typeof userEvent.setup>, trigger: HTMLElement, value: string) {
  await user.click(trigger);
  const options = await screen.findAllByRole("option");
  const option = options.find(item => item.dataset.value === value);
  if (!option) throw new Error(`Missing selectable value: ${value}`);
  await user.click(option);
}
