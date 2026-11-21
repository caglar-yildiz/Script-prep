# Google Play'e yayın rehberi

Bu belge Memo Everything'i (`com.memoeverything.app`) Google Play'e çıkarmak için gereken adımları sırayla listeler. Kod tarafı hazır; aşağıdaki işlerin çoğu Play Console'da senin yapman gereken adımlar.

## 0. Özet takvim

1. Yükleme anahtarı oluştur ve GitHub'a ekle (15 dk).
2. İlk imzalı AAB'yi üret, Play Console'da uygulamayı oluşturup **Dahili test**e yükle (1 saat).
3. Abonelik ürününü oluştur, lisans test kullanıcısıyla satın almayı dene (1 saat).
4. Mağaza sayfası, gizlilik politikası, Data safety, içerik derecelendirmesi (2–3 saat).
5. **Kapalı test**: en az 12 test kullanıcısı, kesintisiz 14 gün (zorunlu, kişisel hesap).
6. Üretime erişim başvurusu → onay → yayın.

## 1. Yükleme anahtarı (upload key)

Kendi bilgisayarında bir kez çalıştır ve dosyayı güvenli bir yerde yedekle. Bu anahtar kaybolursa Play Console'dan sıfırlama istemek gerekir.

```sh
keytool -genkeypair -v -keystore memo-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 memo-upload.jks > memo-upload.b64   # macOS: base64 -i memo-upload.jks -o memo-upload.b64
```

GitHub → depo → Settings → **Environments → play-signing** → **Add environment secret** (iş akışları bu ortamı kullanır):

| Secret | Değer |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `memo-upload.b64` dosyasının içeriği |
| `RELEASE_KEYSTORE_PASSWORD` | keystore parolası |
| `RELEASE_KEY_ALIAS` | `upload` |
| `RELEASE_KEY_PASSWORD` | anahtar parolası |

Play Console'da **Play App Signing** açık kalsın (varsayılan): Google asıl imzalama anahtarını saklar, sen yalnızca yükleme anahtarını kullanırsın.

## 2. İmzalı App Bundle (AAB)

- GitHub → Actions → **Build Play Store bundle** (`main.yml`, yalnızca `main` dalında) → *Run workflow*,
  ya da sürüm etiketi gönder: `git tag v0.3.0 && git push origin v0.3.0` (**Release bundle**, `release.yml`).
- İş bitince **Artifacts** altından `Memo-Everything-…aab` dosyasını indir.
- Sürüm kodu her çalıştırmada otomatik artar (`100 + çalıştırma numarası`).

## 3. Play Console'da uygulama

1. **Uygulama oluştur**: ad *Memo Everything*, varsayılan dil İngilizce (ya da Türkçe), Uygulama, Ücretsiz.
2. **Test ve yayın → Dahili test** → yeni sürüm → AAB'yi yükle. Paket adı ilk yüklemeyle `com.memoeverything.app` olarak kalıcı hale gelir.
3. Kendini dahili test kullanıcısı olarak ekle ve test bağlantısıyla uygulamayı Play'den kur.

## 4. Abonelik ürünü

Abonelik ancak Billing iznine sahip bir AAB yüklendikten sonra oluşturulabilir.

**Para kazanma → Ürünler → Abonelikler → Abonelik oluştur**

| Alan | Değer |
| --- | --- |
| Ürün kimliği | `memo_premium` |
| Ad | Memo Everything Premium |
| Temel plan 1 | Kimlik `monthly`, otomatik yenilenen, faturalandırma dönemi 1 ay |
| Temel plan 2 | Kimlik `yearly`, otomatik yenilenen, faturalandırma dönemi 1 yıl |

Kimlikler kodla birebir aynı olmalı (`Billing.java`). İstersen bir temel plana **ücretsiz deneme** teklifi ekle (ör. 7 gün); uygulama bunu kendisi algılar ve "7 gün ücretsiz deneme" yazar. Fiyatları ülkelere göre Play Console belirler; sen temel fiyatı gir.

**Test:** Ayarlar → **Lisans testi** → kendi Gmail adresini ekle. Bu hesapla yapılan satın almalar ücretlendirilmez; test abonelikleri dakikalar içinde yenilenir/biter.

> Not: Abonelik durumu şimdilik cihazda doğrulanıyor (sunucu yok). İleride bir sunucuyla Google Play Developer API üzerinden doğrulama eklemek, sahte satın alma riskini azaltır.

## 5. Mağaza sayfası ve politikalar

- **Gizlilik politikası URL'si** zorunlu (mikrofon/kamera izni): `https://caglar-yildiz.github.io/Script-prep/`. Kaynağı `docs/index.html`. Yayınlamak için: GitHub → depo → Settings → Pages → *Deploy from a branch* → `main` / `docs` → Save (birkaç dakika sürer).
- **Uygulama erişimi**: giriş gerekmez. Ekip provası hesap istemiyor (ad yazılıyor, anonim kimlik); Google hesabı bağlamak isteğe bağlı. İnceleme için bir davet kodu verilebilir.
- **Reklamlar**: yok.
- **İçerik derecelendirmesi** anketi: şiddet/kumar vb. yok → genelde "Herkes/3+" çıkar.
- **Hedef kitle**: 13+ (uygulama çocuklara yönelik değil).
- **Data safety** (önerilen yanıtlar, son kararı Play'in açıklamalarına bakarak ver):
  - Toplanan/paylaşılan veri: yalnızca **ekip provası** kullanılırsa, Firebase'de (Google) kullanıcının yazdığı ad, anonim kullanıcı kimliği, Google hesabı bağlanırsa e-posta (hesap yönetimi, uygulama işlevi), paylaşılan metin (diğer kullanıcı içeriği) ve replik ses kayıtları (ses kayıtları, uygulama işlevi). Hepsi isteğe bağlı; satılmıyor, reklam için kullanılmıyor.
  - Ses: konuşma tanıma için cihazın Google hizmeti kullanılıyor; uygulama bu sesi saklamıyor veya göndermiyor. Ekip provasında kaydedilen replikler ise grup üyeleri dinlesin diye Firebase'e yükleniyor.
  - Fotoğraf/video: yalnızca cihazda saklanıyor.
  - Satın alma geçmişi: Google Play Billing üzerinden; Play'in kendi işlediği veriler.
  - Veriler aktarım sırasında şifreleniyor: evet (HTTPS). Kullanıcı verileri silebilir: evet (uygulama içinden).
  - Yedekleme: metinler ve ayarlar Android'in kendi yedeğine (Google hesabı) giriyor; bu, geliştiricinin veri toplaması sayılmaz. Kullanıcı ayrıca dosyaya dışa aktarabiliyor.
- **Görseller**: uygulama simgesi 512×512, öne çıkan görsel 1024×500, en az 2 telefon ekran görüntüsü (her dil için ayrı yüklenebilir).

## 6. Kapalı test (kişisel hesap şartı)

- **Test ve yayın → Kapalı test** → bir kanal oluştur, AAB'yi yükle.
- En az **12 test kullanıcısı** katılmalı ve **14 gün kesintisiz** katılımda kalmalı (Google Grubu ya da e-posta listesi). Tiyatro ekibin bunun için ideal.
- 14 gün dolunca **Üretime erişim başvurusu** formu açılır (test sürecini ve geri bildirimleri soruyor).

## 7. Mağaza metinleri

Başlık ≤ 30, kısa açıklama ≤ 80, tam açıklama ≤ 4000 karakter.

### English (en-US)

**Title:** Memo Everything: Learn Lines

**Short description:** Rehearse your lines with a voice partner. Scripts, speeches and poems.

**Full description:**

Memo Everything is your rehearsal partner for learning lines by heart.

Add your script as a PDF or TXT, paste it or type it. The app finds the characters and scenes for you. Pick your role, and the other characters are read aloud while the app waits for your line.

• Practice and listen modes for scenes, speeches and poems
• Hide your lines, show only the first word or the first letters
• Reveal a line when you get stuck and go back to the cue
• Works in Turkish, English, Spanish, German, French, Portuguese and Italian
• Your texts stay on your phone

Premium
• Natural AI voices generated on your phone, with a different voice for each character
• Hands-free cues: the next line starts when you finish speaking
• Detailed results: see where you stumbled and practise just those lines
• Self-tape recording with a teleprompter and spoken partner lines

Premium is a subscription billed through Google Play. It renews automatically until you cancel.

### Türkçe (tr-TR)

**Başlık:** Memo Everything: Ezber Provası

**Kısa açıklama:** Repliklerini sesli bir partnerle çalış. Oyun, konuşma ve şiir ezberle.

**Tam açıklama:**

Memo Everything, replik ezberlemek için prova partnerin.

Metnini PDF ya da TXT olarak ekle, yapıştır veya yaz. Uygulama karakterleri ve sahneleri senin için bulur. Rolünü seç; diğer karakterlerin replikleri sesli okunur, sıra sana geldiğinde uygulama seni bekler.

• Sahneler, konuşmalar ve şiirler için çalış ve dinle modları
• Repliğini gizle, yalnızca ilk kelimeyi ya da ilk harfleri göster
• Takıldığında repliği aç, ipucu repliğine geri dön
• Türkçe, İngilizce, İspanyolca, Almanca, Fransızca, Portekizce ve İtalyanca
• Metinlerin telefonunda kalır

Premium
• Telefonunda üretilen doğal yapay zekâ sesleri, her karaktere ayrı ses
• Sesle ilerleme: repliğini bitirince sıradaki replik başlar
• Ayrıntılı sonuçlar: takıldığın yerleri gör, yalnızca onları çalış
• Teleprompter ve sesli partner replikleriyle self-tape çekimi

Premium, Google Play üzerinden faturalandırılan bir aboneliktir ve iptal edene kadar otomatik yenilenir.

### Español (es-ES)

**Título:** Memo Everything: Ensaya Texto

**Descripción breve:** Ensaya tus réplicas con un compañero de voz. Guiones, discursos y poemas.

**Descripción completa:**

Memo Everything es tu compañero de ensayo para aprender texto de memoria.

Añade tu guion en PDF o TXT, pégalo o escríbelo. La app detecta los personajes y las escenas. Elige tu papel: los demás personajes se leen en voz alta y la app espera tu réplica.

• Modos ensayar y escuchar para escenas, discursos y poemas
• Oculta tus réplicas o muestra solo la primera palabra o las iniciales
• Muestra la réplica cuando te trabes y vuelve al pie
• En turco, inglés, español, alemán, francés, portugués e italiano
• Tus textos se quedan en tu teléfono

Premium
• Voces IA naturales generadas en tu teléfono, una distinta para cada personaje
• Avance por voz: la siguiente réplica empieza cuando terminas de hablar
• Resultados detallados: ve dónde te trabaste y ensaya solo esas réplicas
• Grabación de self-tape con teleprompter y réplicas del compañero en voz alta

Premium es una suscripción facturada a través de Google Play que se renueva automáticamente hasta que la canceles.

### Deutsch (de-DE)

**Titel:** Memo Everything: Text lernen

**Kurzbeschreibung:** Probe deine Repliken mit einem Sprechpartner. Skripte, Reden und Gedichte.

**Vollständige Beschreibung:**

Memo Everything ist dein Probenpartner, um Text auswendig zu lernen.

Füge dein Skript als PDF oder TXT hinzu, kopiere es hinein oder tippe es ab. Die App erkennt Figuren und Szenen. Wähle deine Rolle: Die anderen Figuren werden vorgelesen, und die App wartet auf deine Replik.

• Proben- und Anhörmodus für Szenen, Reden und Gedichte
• Blende deine Repliken aus oder zeige nur das erste Wort oder die Anfangsbuchstaben
• Zeige die Replik, wenn du hängst, und geh zum Stichwort zurück
• Auf Türkisch, Englisch, Spanisch, Deutsch, Französisch, Portugiesisch und Italienisch
• Deine Texte bleiben auf deinem Telefon

Premium
• Natürliche KI-Stimmen direkt auf deinem Telefon, für jede Figur eine eigene
• Weiter per Stimme: Die nächste Replik startet, wenn du fertig gesprochen hast
• Detaillierte Ergebnisse: Sieh, wo du gehangen hast, und übe nur diese Repliken
• Self-Tape-Aufnahme mit Teleprompter und vorgelesenen Partnerrepliken

Premium ist ein über Google Play abgerechnetes Abo, das sich automatisch verlängert, bis du kündigst.

### Français (fr-FR)

**Titre :** Memo Everything: Répétitions

**Description courte :** Répète ton texte avec un partenaire vocal. Pièces, discours et poèmes.

**Description complète :**

Memo Everything est ton partenaire de répétition pour apprendre ton texte par cœur.

Ajoute ton texte en PDF ou TXT, colle-le ou tape-le. L'app repère les personnages et les scènes. Choisis ton rôle : les autres personnages sont lus à voix haute et l'app attend ta réplique.

• Modes répéter et écouter pour les scènes, discours et poèmes
• Masque tes répliques ou affiche seulement le premier mot ou les premières lettres
• Affiche la réplique quand tu bloques et reviens à la réplique d'appel
• En turc, anglais, espagnol, allemand, français, portugais et italien
• Tes textes restent sur ton téléphone

Premium
• Voix IA naturelles générées sur ton téléphone, une voix différente par personnage
• Avance à la voix : la réplique suivante démarre quand tu as fini de parler
• Résultats détaillés : vois où tu as bloqué et travaille seulement ces répliques
• Self-tape avec téléprompteur et répliques du partenaire lues à voix haute

Premium est un abonnement facturé via Google Play, renouvelé automatiquement jusqu'à résiliation.

### Português (pt-BR)

**Título:** Memo Everything: Decorar Falas

**Descrição curta:** Ensaie suas falas com um parceiro de voz. Roteiros, discursos e poemas.

**Descrição completa:**

O Memo Everything é seu parceiro de ensaio para decorar o texto.

Adicione seu roteiro em PDF ou TXT, cole ou digite. O app encontra os personagens e as cenas. Escolha seu papel: os outros personagens são lidos em voz alta e o app espera a sua fala.

• Modos ensaiar e ouvir para cenas, discursos e poemas
• Oculte suas falas ou mostre só a primeira palavra ou as iniciais
• Mostre a fala quando travar e volte para a deixa
• Em turco, inglês, espanhol, alemão, francês, português e italiano
• Seus textos ficam no seu celular

Premium
• Vozes naturais de IA geradas no seu celular, uma diferente para cada personagem
• Avanço por voz: a próxima fala começa quando você termina de falar
• Resultados detalhados: veja onde travou e pratique só essas falas
• Gravação de self-tape com teleprompter e falas do parceiro em voz alta

O Premium é uma assinatura cobrada pelo Google Play e renovada automaticamente até você cancelar.

### Italiano (it-IT)

**Titolo:** Memo Everything: Prova Battute

**Descrizione breve:** Prova le tue battute con un partner vocale. Copioni, discorsi e poesie.

**Descrizione completa:**

Memo Everything è il tuo partner di prova per imparare il testo a memoria.

Aggiungi il copione in PDF o TXT, incollalo o scrivilo. L'app riconosce personaggi e scene. Scegli il tuo ruolo: gli altri personaggi vengono letti ad alta voce e l'app aspetta la tua battuta.

• Modalità prova e ascolto per scene, discorsi e poesie
• Nascondi le tue battute o mostra solo la prima parola o le iniziali
• Mostra la battuta quando ti blocchi e torna all'attacco
• In turco, inglese, spagnolo, tedesco, francese, portoghese e italiano
• I tuoi testi restano sul telefono

Premium
• Voci IA naturali generate sul telefono, una diversa per ogni personaggio
• Avanzamento a voce: la battuta successiva parte quando finisci di parlare
• Risultati dettagliati: scopri dove ti sei bloccato e ripassa solo quelle battute
• Registrazione di self-tape con gobbo e battute del partner lette ad alta voce

Premium è un abbonamento fatturato tramite Google Play che si rinnova automaticamente finché non lo annulli.
