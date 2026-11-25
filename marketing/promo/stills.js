// Renders chosen moments of the promo page to PNG files for checking the layout.
const { chromium } = require('playwright');
(async () => {
  const [page_, out, ...times] = process.argv.slice(2);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  await page.goto('file://' + page_);
  await page.evaluate(() => document.fonts.ready);
  for (const t of times) {
    await page.evaluate(x => window.render(x), Number(t));
    await page.screenshot({ path: `${out}/still-${t}.png` });
  }
  await browser.close();
})();
