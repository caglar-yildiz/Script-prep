# Tanıtım videosu

`promo.html` bütün sahneleri ve zamanlamayı içerir (`render(t)` sayfayı t. saniyeye getirir). Video Playwright ile kare kare kaydedilip ffmpeg ile MP4'e çevrilir:

```sh
node marketing/promo/render.js "$PWD/marketing/promo/promo.html" marketing/promo/memo-everything-tanitim-9x16.mp4 30
# Yerleşimi kontrol etmek için seçilen anları PNG olarak kaydeder:
node marketing/promo/stills.js "$PWD/marketing/promo/promo.html" /tmp 2.6 10.6 17 27
```

Ekranlar uygulamanın renkleri ve düzeniyle çizilmiş mockup'lardır; replikler uydurmadır. Videoda müzik yoktur (sessiz ses kanalı vardır).
