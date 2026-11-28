# Tanıtım videosu

`promo.html` bütün sahneleri ve zamanlamayı içerir (`render(t)` sayfayı t. saniyeye getirir). Video Playwright ile kare kare kaydedilip ffmpeg ile MP4'e çevrilir:

Yazılar sayfadaki `TEXTS` sözlüğündedir; telefon ekranındaki metinler uygulamanın kendi metinleridir. Dil `?lang=` ile seçilir: Türkçe (varsayılan) ve İngilizce (`?lang=en`).

```sh
node marketing/promo/render.js "$PWD/marketing/promo/promo.html" marketing/promo/memo-everything-tanitim-9x16.mp4 30
node marketing/promo/render.js "$PWD/marketing/promo/promo.html?lang=en" marketing/promo/memo-everything-promo-en-9x16.mp4 30
# Yerleşimi kontrol etmek için seçilen anları PNG olarak kaydeder:
node marketing/promo/stills.js "$PWD/marketing/promo/promo.html" /tmp 2.6 10.6 17 27
```

Ekranlar uygulamanın renkleri ve düzeniyle çizilmiş mockup'lardır; replikler uydurmadır. Videoda müzik yoktur (sessiz ses kanalı vardır).

## Stüdyo sesleri videosu

`studio-voices-promo.html` İngilizce örnek sahnenin ilk 10 repliğini telefonda oynatır; her replikte yönetmenin notu görünür. Ses, uygulamadaki gerçek stüdyo sesleridir (`app/src/main/assets/studio-sample/en-*.m4a`); `render-studio.js` kareleri kaydedip sesleri sayfanın kendi zamanlamasıyla (`STARTS`) altına yerleştirir:

```sh
node marketing/promo/render-studio.js marketing/promo/memo-everything-studio-voices-en-9x16.mp4
```
