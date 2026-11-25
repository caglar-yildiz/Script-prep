# Tanıtım videoları

- `memo-everything-tanitim-9x16.mp4`, `memo-everything-promo-en-9x16.mp4`: genel tanıtım (28 sn, `promo.html`).
- `memo-everything-ekip-provasi-9x16.mp4`, `memo-everything-ensemble-en-9x16.mp4`: ekip provası (30 sn, `ensemble.html`): metni paylaş, davet kodu, roller, repliklerini kaydet, ekibin sesiyle prova.

Her sayfa (`promo.html`, `ensemble.html`) bütün sahneleri ve zamanlamayı içerir (`render(t)` sayfayı t. saniyeye getirir). Video Playwright ile kare kare kaydedilip ffmpeg ile MP4'e çevrilir:

Yazılar sayfadaki `TEXTS` sözlüğündedir; telefon ekranındaki metinler uygulamanın kendi metinleridir. Dil `?lang=` ile seçilir: Türkçe (varsayılan) ve İngilizce (`?lang=en`).

```sh
node marketing/promo/render.js "$PWD/marketing/promo/promo.html" marketing/promo/memo-everything-tanitim-9x16.mp4 30
node marketing/promo/render.js "$PWD/marketing/promo/promo.html?lang=en" marketing/promo/memo-everything-promo-en-9x16.mp4 30
node marketing/promo/render.js "$PWD/marketing/promo/ensemble.html" marketing/promo/memo-everything-ekip-provasi-9x16.mp4 30
node marketing/promo/render.js "$PWD/marketing/promo/ensemble.html?lang=en" marketing/promo/memo-everything-ensemble-en-9x16.mp4 30
# Yerleşimi kontrol etmek için seçilen anları PNG olarak kaydeder:
node marketing/promo/stills.js "$PWD/marketing/promo/promo.html" /tmp 2.6 10.6 17 27
```

Ekranlar uygulamanın renkleri ve düzeniyle çizilmiş mockup'lardır; replikler uydurmadır. Videoda müzik yoktur (sessiz ses kanalı vardır).
