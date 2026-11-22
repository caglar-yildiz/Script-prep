# Ekip provası: Firebase kurulumu

Ekip provası Firebase'in **ücretsiz Spark planında** çalışır; kart tanımlamaya gerek yok. Kota dolarsa servis o gün durur, fatura gelmez.

Kullanılan servisler: **Authentication** (anonim kimlik, isteğe bağlı Google hesabı) ve **Cloud Firestore** (gruplar, roller, replik kayıtları). Cloud Storage kullanılmıyor, çünkü Spark planında açılamıyor. Replik kayıtları Firestore belgelerinde saklanıyor: AAC, 32 kbit/s mono, replik başına en fazla 10 dakika. Bir belge en fazla 1 MiB alabildiği için uzun bir monolog 800 KB'lık parçalara bölünüyor (3 dakika ≈ 720 KB tek belge, 10 dakika ≈ 2,4 MB üç belge). Parçalar tek seferde yazılıyor, bu yüzden bir cihaz hiçbir zaman yarım kayıt görmüyor.

## 1. Proje ve Android uygulaması

Proje: `memo-70a45`. Android uygulaması: `com.memoeverything.app`.

**Proje ayarları → Genel → Android uygulaması → SHA sertifika parmak izi ekle** bölümüne şu parmak izlerini ekle:

| Anahtar | SHA-1 |
| --- | --- |
| Debug (`app/debug.keystore`, CI APK'ları) | `87:A0:6B:18:E8:D2:E6:77:B5:C9:F5:7B:DA:D7:85:26:AF:50:31:C7` |
| Play uygulama imzalama anahtarı | Play Console → Test ve yayın → Kurulum → **Uygulama imzalama** sayfasından kopyala |
| Yükleme anahtarı (isteğe bağlı) | Aynı sayfadan |

Play Store'dan kurulan uygulama Play'in imzalama anahtarıyla imzalanır. Bu anahtarın SHA-1'i eklenmezse mağaza sürümünde Google ile giriş çalışmaz.

## 2. Giriş yöntemleri

Kullanıcılar hesap açmaz: adını yazar, uygulama bu kuruluma **anonim** bir kimlik verir. Aynı kimliği tablette ya da yeni telefonda kullanmak isteyen, **Google hesabını bağlar**. Kimlik değişmez, başka cihazda Google ile giriş yapınca aynı gruplar, roller ve kayıtlar açılır.

**Authentication → Sign-in method** sekmesinde:

1. **Anonymous → Etkinleştir → Kaydet.** Bu olmadan kimse başlayamaz.
2. **Google → Etkinleştir**, bir destek e-postası seç ve **Kaydet**. Bu yalnızca hesap bağlama ve başka cihazdan giriş için gerekiyor.

Ardından **`google-services.json` dosyasını yeniden indir** ve `app/google-services.json` dosyasının yerine koy. Google girişi açılmadan indirilen dosyada `oauth_client` listesi boş oluyor. O durumda uygulama "Google ile giriş bu uygulama için henüz ayarlanmadı" uyarısı verir.

## 3. Firestore

1. **Firestore Database → Veritabanı oluştur**. Konum için `eur3` ya da `europe-west1` uygun. Konum sonradan değiştirilemiyor.
2. **Rules** sekmesine depodaki [`firestore.rules`](../firestore.rules) dosyasının içeriğini yapıştır ve **Yayınla**.

Kurallar özetle şunları sağlıyor:

- Bir grubun metnini, üyelerini ve kayıtlarını yalnızca o grubun üyeleri görür.
- Gruba yalnızca davet kodunu bilen katılabilir. Davet kodları listelenemez.
- Herkes yalnızca kendi rollerini ve kendi kayıtlarını değiştirir.
- Metni yalnızca grup sahibi değiştirir ve grubu yalnızca o siler.

Kurallar Firebase emülatöründe uygulamanın yaptığı işlemlerle (oluşturma, katılma, rol seçme, kayıt, ayrılma, silme) ve izinsiz denemelerle test edildi.

## 4. Ücretsiz kota (Spark)

| Kaynak | Günlük / toplam ücretsiz |
| --- | --- |
| Firestore depolama | 1 GiB |
| Belge okuma | 50.000 / gün |
| Belge yazma | 20.000 / gün |
| Belge silme | 20.000 / gün |
| Ağ çıkışı | 10 GiB / ay |

1000 replikli bir oyunun tüm kayıtları yaklaşık 15–25 MB tutar. Kayıtları ilk kez indiren her üye replik sayısı kadar okuma yapar. Sonrasında yalnızca yeni, değişen ya da silinen kayıtlar indirilir ve telefonda saklanır; prova çevrimdışı çalışır.

Kullanımı **Firestore Database → Kullanım** sekmesinden takip edebilirsin.

## Uygulamada akış

1. Ana ekranda **Ekip provası** → adını yaz → **Başla**. Davet linkiyle gelen kişi doğrudan katılır.
2. **Metin paylaş** → kütüphanedeki karakterli bir metni seç. Grup oluşur ve 6 haneli bir davet kodu çıkar.
3. **Kodu gönder** ile kodu ve davet linkini ekibe yolla. Link (`https://caglar-yildiz.github.io/Script-prep/join/?c=KOD`) uygulamayı açar, uygulama yüklü değilse Google Play'e götürür. Linkle gelmeyenler **Kodla katıl** ile kodu yazar. Link sayfası `docs/join/index.html`; GitHub Pages `main` dalından yayımladığı için birleştirmeden sonra çalışır.
4. Herkes **Rollerini seç** ile rollerini işaretler. Grup ekranında hangi rolü kimin aldığı görünür.
5. Sağ üstteki menüden ad değiştirilir ve **Google hesabını bağla** seçilir. Tablette **Google ile giriş yap** aynı kimliği açar.
6. **Repliklerimi kaydet**: oyuncu yalnızca kendi rollerinin repliklerini, üstte ipucu repliğiyle birlikte sırayla görür; kaydeder, dinler, yeniden çeker ya da siler. Aynı replik birden çok yerde geçiyorsa bir kez kaydedilir. Kayıt önce telefona yazılır, sonra yüklenir; internet yoksa bağlantı gelince gönderilir.
7. **Prova et**: önce ekibin yeni kayıtları indirilir, sonra metnin kopyası kütüphaneye kaydedilir (`ensemble:` önekiyle) ve seçilen ilk rolle prova açılır. Kaydı olan replik oyuncunun sesiyle, olmayan cihaz sesi ya da yapay zekâ sesiyle okunur. Bir rolü birden çok kişi kaydettiyse (A/B kadro) o rolün en çok replik kaydeden kişinin sesi kullanılır, eksik kalan replikler diğerinin sesiyle tamamlanır.

Kayıtlar replik metnine bağlı: metinde bir repliğin sözleri değişirse o kaydı artık eşleşmez, yeniden kaydedilmesi gerekir.

## Sıradaki aşama

- Provada hangi kadronun (A/B) sesinin çalacağını seçme.
- Grup sahibinin metni güncellemesi ve değişen repliklerin kayıtlarını "güncel değil" olarak işaretleme.
- Hesabı ve tüm verileri uygulama içinden silme (iOS'a çıkmadan önce zorunlu).
