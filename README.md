# Memo Everything

Script Prep, tiyatro oyuncularının tek başına replik çalışabilmesi için planlanan bir mobil prova uygulaması. Oyuncu metnini içe aktarır, rolünü seçer ve diğer karakterlerin repliklerini dinleyerek sahneyi prova eder.

## Şimdi yapılacaklar

İlk hedef, Android telefonda örnek bir sahneyi açıp rol seçmek, diğer karakterleri Türkçe cihaz sesiyle dinlemek ve kendi repliğinden sonra düğmeyle devam etmek. Bu akış çalışınca PDF aktarımını aynı metin modeline bağla.

- [ ] **Rakipleri aynı metinlerle dene.** Çok karakterli bir sahne, çok sahneli uzun oyun ve taranmış bir metin seç. İki uygulamada ilk provaya kadar geçen süreyi, yanlış role atanan replikleri, elle düzeltme süresini ve prova sırasındaki hatalı ilerlemeyi kaydet. Kamuya açık test dosyalarında özgün veya paylaşım izni olan metinler kullan.
- [ ] **Oyun kütüphanesi ve metin modeli.** Oyun, perde/sahne, karakter, replik ve sahne yönergesini cihazda sakla. Uygulama kapanıp açıldığında metin, rol seçimi ve son prova konumu geri gelsin. Hazır bir örnek sahneyle ilk prova dosyasız açılabilsin.
- [ ] **Metin ve metin katmanlı PDF aktarımı.** Yapıştırılan metni, TXT ve PDF dosyasını destekle. `KARAKTER: replik` ve ayrı satırdaki rol adı biçimlerini, çok satırlı replikleri, Türkçe karakterleri ve parantez içi yönergeleri dene. Taramayı algılayınca metin yapıştırma seçeneğini göster. Önizlemede yanlış rolü, sahne ayrımını ve replik sınırını düzeltmek mümkün olsun.
- [ ] **Rol seçimi ve çalışan prova döngüsü.** Sahne/seçili aralığı başlat. Dinle modunda tüm rolleri oku. Prova modunda diğer rolleri cihazın Türkçe sesiyle oku ve oyuncunun sırasında bekle. İleri, geri, tekrarla, duraklat ve ipucu kontrolleri olsun. Türkçe çevrimdışı sesi kurulu cihazda uçak modunda prova tamamlanabilsin. Ses yoksa görsel prova kullanılabilsin.
- [ ] **Ezber görünümü.** Oyuncu sahne içinde tam replik, ilk kelimeler, ilk harfler ve gizli replik arasında geçebilsin. Takıldığı anda o repliği açıp önceki partner repliğinden tekrar başlayabilsin.
- [ ] **Tiyatro ekibiyle beta.** Önerilen çıkış koşulu 5 oyuncudan en az 4’ünün yönlendirme almadan örnek veya kendi sahnesiyle tam bir prova bitirmesi. İlk provaya kadar geçen süreyi, düzeltme ihtiyacını ve yarıda bırakılan adımı gözlemle.

## Sonradan yapılacaklar

### Prova özellikleri

- [ ] **Türkçe sesle otomatik ilerleme.** Sesli okuma bitince mikrofonu aç. Anlamlı duraklama veya kısa sessizlikte sırayı erkenden geçirme. İzin reddi ve tanıma sorunlarında düğmeyle ilerlemeyi koru. Doğal duraklama, fısıltı ve arka plan gürültüsü içeren 50 denemede erken geçişi, kaçırılan geçişi ve gecikmeyi ayrı ölç. İlk hedef en fazla 2 erken geçiş.
- [ ] **Script Rehearser tarzı prova seçenekleri.** Ayarlanabilir süreyle otomatik ilerleme, süre sonunda doğru repliği sesli tekrar, karaktere ayrı ses ve tek repliğe kaydedilmiş ses atama.
- [ ] **Daha fazla kaynak biçimi.** Önce taranmış PDF ve fotoğraf için Türkçe OCR, sonra DOCX, Fountain ve FDX. Düşük güvenli satırları önizlemede işaretle. Kullanıcı kaynak sayfaya bakıp aynı ekranda düzeltebilsin.
- [ ] **Prova sonucu.** İpucuyla açılan, yeniden denenen ve tanınan replikleri oturum sonunda göster. Konuşma metniyle karşılaştırmanın belirsiz kaldığı satırları belirt.
- [ ] **Self-tape ve dışa aktarma.** Önde/arkada kamera, sahneye göre ilerleyen teleprompter, partner sesini kapatma, çekim öncesi ses kontrolü ve son replikten sonra kayıt payı. Videoyu yerelde tut ve kullanıcı paylaşınca dışa aktar. Prova dosyasını ses kayıtlarıyla yedekle ve geri yükle.
- [ ] **Doğal karakter sesleri ve ticari model.** Türkçe sesleri oyuncularla dinleme testinde değerlendir. Sahne başına üretim maliyetini ve önbellek etkisini ölç. Temel cihaz sesiyle prova ücretsiz devam ederken doğal ses paketi modelini değerlendir.

### Uzun oyunlar ve ekip provası

- [ ] **Tam oyun hazırlığını hızlandır.** Uzun Türkçe oyunlarda perde/sahne başlıklarını, lakapları, birden fazla rolü ve sahne yönergelerini tanı. Aynı karakterin farklı adlarını tek işlemle birleştir. Aynı test metinlerinde rakiplerle kıyaslandığında medyan elle düzeltme süresini en az %30 düşürmeyi hedefle.
- [ ] **Takıldığım yerleri çalış.** İpucu istenen veya yeniden denenen replikleri çağrı repliğiyle birlikte kısa bir prova kuyruğuna al. Oyuncu bütün sahneyi başa sarmadan bu kesitleri tekrar edebilsin.
- [ ] **Tiyatro ekibiyle ortak çalışma.** Sahne ve rol bazında erişim, metin sürümleri, yönetmen notları ve replik değişikliklerini gösterme. Yeni sürüm geldiğinde oyuncunun kişisel notları ve prova konumu korunabilsin. İhtiyacı doğrulamak için önce tek bir ekipte pilot yap.
- [ ] **iOS kapsamını beta sonrası belirle.** Android betasında hangi özelliklerin düzenli kullanıldığını ve iOS talebini ölç. Ortak dosya biçimi ve prova kuralları üzerinden port kapsamını çıkar.

Rakip karşılaştırması ve ayrıntılı geliştirme takibi [Issue #1](https://github.com/caglar-yildiz/Script-prep/issues/1) üzerinden yapılır.

## Android geliştirme

Android Studio'da bu depoyu aç. Derleme için JDK 17 veya üzeri, Android SDK 36 ve Build Tools 36.0.0 gerekir.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

İlk derlemede sherpa-onnx AAR dosyası (~50 MB) GitHub sürümünden `app/libs/` klasörüne indirilir ve SHA-256 ile doğrulanır; bu klasör depoya eklenmez.

İlk açılışta **İlk metnini oluştur** düğmesine dokun ve bir kategori seç. PDF (metin katmanlı ya da taranmış) veya UTF-8 TXT dosyası içe aktarabilir, metni yapıştırabilir ya da kendin yazabilirsin. İlk metin kaydedildikten sonra yeni metinler ana ekrandaki **Yeni metin** düğmesiyle eklenir; Ayarlar sağ üstteki dişli simgesinden açılır. Bir metni silmek için kartındaki **⋮** menüsünden (veya karta uzun basarak) **Sil**'i seç; düzenleme ve prova ekranlarındaki çöp kutusu simgesi de aynı işi yapar. Silme işlemi birkaç saniye boyunca **Geri al** ile geri alınabilir. PDF aktarımında algılanan sahneleri ve karakterleri gözden geçirip metni düzenleyebilirsin. Sahne veya tüm metin üzerinden prova yapabilirsin. Türkçe ses yoksa karşı replik ekranda kalır ve **Devam et** düğmesiyle prova sürdürülebilir.

### Prova ve düzenleme

- Provada sıra oyuncuya uzaksa **Repliğine geç** düğmesi, oyuncunun sıradaki repliğinden 3 replik öncesine atlar; böylece ipucu replikleri duyulur. Üst çubuktaki liste simgesi **Sahne akışı**nı açar: bütün replikler kaydırılabilir listede, oyuncunun replikleri vurgulu; dokunulan replikten prova devam eder.
- Rol metinleri **Replikleri düzenle** ekranında replik replik düzenlenir: karakter, metin ve repliğin ardından gelen notlar değiştirilebilir; ⋮ menüsüyle araya replik veya not eklenir, replik silinir (geri alınabilir). Düzenlenen replikler `Scene.toText` ile metne geri yazılır ve aynı satırlar olarak yeniden okunur. Düz metin düzenleyicisi üst çubuktan açılmaya devam eder.
- **Karakterleri düzenle** (replik düzenleme ekranındaki kişiler simgesi ya da içe aktarma sonrası önizlemedeki düğme) karakterleri replik sayılarıyla listeler. Bir karaktere dokunup adını düzeltirsin; başka bir karakterin adını yazar ya da seçersen replikleri o karakterle birleşir (taramada farklı okunan `CLBANTE`, `GCLEBANTE` → `CLÉANTE` gibi). Değişiklik geri alınabilir; yeniden adlandırılan karakterin sesi korunur.

### Ekip provası

Ana ekrandaki **Ekip provası** kartı, hesap açmadan (ad yazıp anonim kimlikle) bir metni ekiple paylaşmayı ya da davet linki veya koduyla bir gruba katılmayı sağlar. Google hesabı bağlanırsa aynı kimlik tablette ve yeni telefonda da açılır. Herkes kendi rollerini seçer ve **Repliklerimi kaydet** ekranında kendi repliklerini ipucu repliğiyle birlikte kaydeder (replik başına 10 dakikaya kadar). **Prova et** ekibin kayıtlarını indirir ve metnin çevrimdışı kopyasıyla provayı açar: kaydı olan replik oyuncunun sesiyle, olmayan cihaz sesiyle çalar. Altyapı Firebase'in ücretsiz Spark planıdır: Authentication ve Cloud Firestore. Kurulum adımları ve güvenlik kuralları [docs/ensemble-setup.md](docs/ensemble-setup.md) ve [firestore.rules](firestore.rules) dosyalarında. `app/google-services.json` yoksa uygulama derlenir ve kart gizlenir.

### Yedekleme

Metinler ve ayarlar Android otomatik yedeklemesine dahil (yapay zekâ ses modeli ve indirme kayıtları hariç), böylece yeniden kurulumda ya da yeni telefonda geri gelir. **Ayarlar → Yedekleme** ile metinler bir JSON dosyasına dışa aktarılıp içe aktarılabilir; içe aktarma aynı kimlikli metinleri günceller, diğerlerini ekler, hiçbir şeyi silmez. Debug derlemeleri depodaki sabit `app/debug.keystore` ile imzalanır; böylece CI'dan gelen her APK öncekinin üzerine güncelleme olarak kurulur.

### Diller

Arayüz Türkçe, İngilizce, İspanyolca, Almanca, Fransızca, Portekizce (Brezilya) ve İtalyanca. Her metnin ayrıca bir **metin dili** var (düzenleyicideki dil çipi); yeni, içe aktarılan veya yapıştırılan metinlerin dili otomatik algılanır (algılanamazsa arayüz dili kullanılır), önceki metinler Türkçe kabul edilir. Cihaz sesi, yapay zekâ sesi, sesle ilerleme ve sahne başlığı tanıma (ACTO/ESCENA, AKT/SZENE, ACTE/SCÈNE, ATO/CENA, ATTO/SCENA) bu dile göre çalışır. Cihaz sesi her dil için **Ayarlar → Prova sesi → Ses dili** ile ayrı seçilir.

### Premium

Premium, Google Play aboneliğiyle açılır. Debug derlemelerinde **Ayarlar → Premium** ekranının altındaki **Test modu** kartından Premium satın almadan açılıp kapatılabilir (release derlemelerinde bu kart görünmez). Premium açıkken prova seçeneklerinde:

- **Karakter sesleri:** Her karaktere ayrı Türkçe ses, ton (kalın/normal/ince) ve hız. Varsayılan dağıtım otomatik; seçimler metin başına kaydedilir.
- **Sesle otomatik ilerleme:** Sıra sendeyken mikrofon dinler; repliğin en az %60'ı duyulunca prova kendiliğinden ilerler. Mikrofon izni gerekir; tanıma yoksa **Devam et** düğmesi çalışmaya devam eder.
- **Ayrıntılı prova sonuçları:** İpucu açılan, geri dönülen ve eksik söylenen replikler sahne sonunda listelenir; **Bu replikleri çalış** yalnızca onları ipucu replikleriyle birlikte prova ettirir.
- **Yapay zekâ sesi:** [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) (OpenRAIL-M) modeli [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ile cihazda çalışır. **Ayarlar → Yapay zekâ sesi** ile bir kez indirilir (~145 MB, SHA-256 ile doğrulanır), sonra çevrimdışıdır. 10 farklı Türkçe ses karakterlere dağıtılır; sıradaki partner replikleri önceden üretilir. Model yüklenemezse cihaz sesine geri dönülür.
- **Kamera provası:** Ön/arka kamerayla self-tape; üstte teleprompter, partner replikleri sesli okunur (kapatılabilir), son replikten sonra 2 sn kayıt payı. Videolar **Filmler › Memo Everything** klasörüne kaydedilir.

### Google Play

Uygulama kimliği `com.memoeverything.app`. Premium, Google Play Billing ile aylık (`monthly`) ve yıllık (`yearly`) temel planları olan `memo_premium` aboneliğidir. İmzalı App Bundle, `v*` etiketiyle tetiklenen **Release bundle** iş akışında üretilir. Yayın adımları, Play Console ayarları ve 7 dilde mağaza metinleri: [docs/play-store.md](docs/play-store.md). Gizlilik politikası: https://caglar-yildiz.github.io/Script-prep/ (kaynak [docs/index.html](docs/index.html)).

### Metin ayrıştırma

- `Jack: Replik`, `JACK:` ve bir sonraki satırda replik.
- `JACK - Replik`, `JACK – Replik`, `JACK — Replik` ve tek başına büyük harfli karakter adı. PDF’de kalın karakter adları da biçim ipucu olarak kullanılır.
- `I. PERDE`, `1. Sahne`, `MUHÂVERE` veya `EXT. BAHÇE - GECE` gibi bölüm başlıkları.
- Parantez, köşeli parantez veya yalnızca italik satırla verilen yönergeler provada ayrıca gösterilir.
- PDF sınırı 100 MB ve 1.000 sayfa. 30 sayfadan uzun PDF'lerde hangi sayfaların aktarılacağı sorulur (bir seferde en fazla 200 sayfa); böylece oyunun başladığı sayfa seçilir, önsöz ve notlar dışarıda kalır. Metin sınırı (150.000 karakter) aşılırsa aktarma son tam sayfada durur, önizleme kalan sayfaları ayrı metin olarak aktarmayı söyler ve başlığa sayfa aralığı eklenir (`Tartuffe (50–112)`).
- Taranmış PDF sayfaları (metin katmanı olmayan ya da 20 harften az metin içeren sayfalar) cihazda ML Kit metin tanımayla okunur (`PdfOcr`); sayfa görüntüsü telefondan çıkmaz. `OcrText` tanınan satırları sıraya koyar: iki kitap sayfasının yan yana tarandığı görüntüleri sütunlara ayırır, `84 TARTUFFE` gibi sayfa başlıklarını ve numaralarını atar, satır sonunda bölünen kelimeleri birleştirir, satır başındaki leke işaretlerini temizler, `ORGON. —` ve `MADAM PERNELLE, —` biçimlerini `ORGON:` yapar, sahne başındaki büyük harfli kişi listesini not olarak alır, `SAHNE Il` gibi okunuşları `SAHNE II` yapar ve tek harfi farklı okunan karakter adlarını en sık okunan yazımda birleştirir. Önizleme kaç sayfanın taranarak okunduğunu söyler; karakter adlarını kontrol etmek gerekir.

Biçim örnekleri [MEB Karagöz ünitesi](https://ogmmateryal.eba.gov.tr/panel/upload/etkilesimli/kitap/turkdiliveedebiyati/10/unite6/files/basic-html/page9.html), [TDK konuşma çizgisi](https://yazim.tdk.gov.tr/content/09-noktalama-isaretleri.html), [Dramatists Guild](https://www.dramatistsguild.com/sites/default/files/2020-01/General-SFI-Formatting-Guidelines-Complete.pdf) ve [Fountain](https://fountain.io/syntax/) belgeleriyle karşılaştırıldı.
