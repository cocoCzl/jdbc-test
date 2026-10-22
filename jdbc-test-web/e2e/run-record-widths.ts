import { expect, type Page } from "@playwright/test";

/** Check rendered geometry, including long strings; independent of the CSS declarations. */
export async function checkRunRecordWidths(page: Page) {
  const table = page.locator(".run-table--history");
  const geometry = await table.evaluate(table => ({
    viewport: innerWidth,
    width: table.getBoundingClientRect().width,
    columns: [...table.querySelectorAll("th")].map(cell => cell.getBoundingClientRect().width),
    fits: table.parentElement!.scrollWidth <= table.parentElement!.clientWidth,
  }));
  expect(geometry.columns).toHaveLength(9);
  expect(geometry.columns[0] / geometry.width).toBeGreaterThanOrEqual(.22);
  expect(geometry.columns[0] / geometry.width).toBeLessThanOrEqual(.26);
  expect(geometry.columns[0]).toBeGreaterThanOrEqual(260);
  expect(geometry.columns[0]).toBeLessThanOrEqual(480);
  if (geometry.viewport >= 1366) expect(geometry.fits).toBe(true);
  const header = await table.locator("th").last().boundingBox();
  for (const row of await table.locator("tbody tr").all()) {
    const eye = await row.locator(".resource-action").boundingBox();
    expect(eye!.width).toBe(44);
    expect(Math.abs(eye!.x + eye!.width / 2 - header!.x - header!.width / 2)).toBeLessThan(1);
    const metrics = await row.locator(".run-result-metric").evaluateAll(nodes => nodes.map(node => {
      const label = node.querySelector(".run-result-label")!.getBoundingClientRect();
      const value = node.querySelector(".run-result-value")!.getBoundingClientRect();
      return {labelRight: label.right, valueLeft: value.left, valueRight: value.right, metricRight: node.getBoundingClientRect().right};
    }));
    for (const metric of metrics) {
      expect(metric.valueLeft - metric.labelRight).toBeGreaterThanOrEqual(5);
      expect(metric.valueRight).toBeLessThanOrEqual(metric.metricRight + .5);
    }
    const result = await row.locator(".run-results").boundingBox();
    const duration = await row.locator("td").nth(6).locator("span").boundingBox();
    expect(duration!.x - result!.x - result!.width).toBeGreaterThanOrEqual(16);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  return geometry;
}
