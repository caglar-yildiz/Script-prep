# Ekip provası: Firebase kurulumu

Ekip provası Firebase'in **ücretsiz Spark planında** çalışır; kart tanımlamaya gerek yok. Kota dolarsa servis o gün durur, fatura gelmez.

Kullanılan servisler: **Authentication** (Google ile giriş) ve **Cloud Firestore** (gruplar, roller, replik kayıtları). Cloud Storage kullanılmıyor, çünkü Spark planında açılamıyor. Replik kayıtları kısa olduğu için Firestore belgelerinde saklanıyor (kayıt başına en fazla ~900 KB).

## 1. Proje ve Android uygulaması

Proje: `memo-70a45`. Android uygulaması: `com.memoeverything.app`.

**Proje ayarları → Genel → Android uygulaması → SHA sertifika parmak izi ekle** bölümüne şu parmak izlerini ekle:

| Anahtar | SHA-1 |
| --- | --- |
| Debug (`app/debug.keystore`, CI APK'ları) | `87:A0:6B:18:E8:D2:E6:77:B5:C9:F5:7B:DA:D7:85:26:AF:50:31:C7` |
| Play uygulama imzalama anahtarı | Play Console → Test ve yayın → Kurulum → **Uygulama imzalama** sayfasından kopyala |
| Yükleme anahtarı (isteğe bağlı) | Aynı sayfadan |

Play Store'dan kurulan uygulama Play'in imzalama anahtarıyla imzalanır. Bu anahtarın SHA-1'i eklenmezse mağaza sürümünde Google ile giriş çalışmaz.

## 2. Google ile giriş

**Authentication → Sign-in method → Google → Etkinleştir**, bir destek e-postası seç ve kaydet.

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

1000 replikli bir oyunun tüm kayıtları yaklaşık 15–25 MB tutar. Kayıtları ilk kez indiren her üye replik sayısı kadar okuma yapar. Sonrasında yalnızca değişen kayıtlar indirilir (bir sonraki aşama).

Kullanımı **Firestore Database → Kullanım** sekmesinden takip edebilirsin.

## Uygulamada akış

1. Ana ekranda **Ekip provası** → **Google ile giriş yap**.
2. **Metin paylaş** → kütüphanedeki karakterli bir metni seç. Grup oluşur ve 6 haneli bir davet kodu çıkar.
3. **Kodu gönder** ile kodu ekibe yolla. Ekip üyeleri **Kodla katıl** ile gruba girer.
4. Herkes **Rollerini seç** ile rollerini işaretler. Grup ekranında hangi rolü kimin aldığı görünür.
5. **Prova et**, metnin bir kopyasını kütüphaneye kaydeder (`ensemble:` önekiyle, çevrimdışı çalışır) ve seçilen ilk rolle provayı açar.

## Sıradaki aşama

- Replik kaydetme ekranı: kendi repliklerini ipucu repliğiyle birlikte görüp kaydetme, dinleme, yeniden çekme.
- Provada kaydı olan replikte oyuncunun sesi, olmayanda cihaz sesi.
- Grup sahibinin metni güncellemesi ve değişen repliklerin kayıtlarını "güncel değil" olarak işaretleme.
- Hesabı ve tüm verileri uygulama içinden silme (iOS'a çıkmadan önce zorunlu).
