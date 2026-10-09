import type { Locator, Page } from "@playwright/test";
export async function chooseOption(page: Page, trigger: Locator, value: string) {
  await trigger.click();
  await page.getByRole("listbox").locator(`[data-value=${JSON.stringify(value)}]`).click();
}
