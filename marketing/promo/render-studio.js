// Renders the studio voices promo (studio-voices-promo.html) frame by frame with Playwright, then
// lays the sample scene's real studio voice audio under it at the page's own STARTS and makes one MP4.
//   node marketing/promo/render-studio.js marketing/promo/memo-everything-studio-voices-en-9x16.mp4
const { chromium } = require('playwright');
const { spawn, execFileSync } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');
(async () => {
  const output = process.argv[2] || 'memo-everything-studio-voices-en-9x16.mp4';
  const fps = 30;
  const page_ = path.resolve(__dirname, 'studio-voices-promo.html');
  const audioDir = path.resolve(__dirname, '../../app/src/main/assets/studio-sample');
  const silent = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'promo-')), 'video.mp4');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  await page.goto('file://' + page_);
  await page.evaluate(() => document.fonts.ready);
  const { duration, starts, lines } = await page.evaluate(() => ({
    duration: window.DURATION, starts: window.STARTS, lines: window.LINES }));
  const ffmpeg = spawn('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'image2pipe', '-framerate', String(fps), '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p', silent], { stdio: ['pipe', 'inherit', 'inherit'] });
  const frames = Math.round(duration * fps);
  for (let i = 0; i < frames; i++) {
    await page.evaluate(t => window.render(t), i / fps);
    const png = await page.screenshot({ type: 'png' });
    if (!ffmpeg.stdin.write(png)) {
      await new Promise(resolve => ffmpeg.stdin.once('drain', resolve));
    }
  }
  ffmpeg.stdin.end();
  await new Promise(resolve => ffmpeg.on('close', resolve));
  await browser.close();
  // Each line's audio delayed to its start, mixed, under the video.
  const inputs = lines.flatMap(line => ['-i', path.join(audioDir, line.audio)]);
  const delays = lines.map((_, i) => `[${i + 1}:a]adelay=${Math.round(starts[i] * 1000)}:all=1[a${i}]`).join(';');
  const mix = lines.map((_, i) => `[a${i}]`).join('') + `amix=inputs=${lines.length}:normalize=0,apad[aout]`;
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', silent, ...inputs, '-filter_complex', `${delays};${mix}`,
    '-map', '0:v', '-map', '[aout]', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-ac', '2',
    '-shortest', '-movflags', '+faststart', output], { stdio: 'inherit' });
})();
