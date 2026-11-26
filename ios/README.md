# Memo Everything — iPhone uygulaması

Android uygulamasının SwiftUI ile yazılmış iOS sürümü. Ekranlar, prova akışı, ayarlar ve 7 dildeki metinler Android ile aynıdır; metin ayrıştırıcı, tarama temizliği, Word/Fountain/FDX dönüştürücüleri ve yedek dosyası biçimi satır satır aynı kurallarla çalışır. Bir telefondaki yedek dosyası diğerine aktarılabilir; ekip provası grupları iki platformda ortaktır.

## Derleme

Gerekenler: macOS, Xcode 16 veya üzeri, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
cd ios
brew install xcodegen
xcodegen generate
open MemoEverything.xcodeproj
```

Xcode projesi depoya eklenmez; `project.yml` dosyasından üretilir. İlk açılışta Swift paketleri indirilir (sherpa-onnx, Firebase, Google Sign-In). Gerçek cihazda çalıştırmak için Xcode'da **Signing & Capabilities** altında kendi ekibini seç.

Metin kurallarının testleri (Android'deki JUnit testlerinin aynısı):

```sh
cd ios/MemoCore
swift test
```

**iOS app** iş akışı (`.github/workflows/ios.yml`) her `ios/` değişikliğinde testleri çalıştırır, uygulamayı simülatör ve iPhone için derler ve imzasız `Memo-Everything-unsigned.ipa` dosyasını yükler.

## Yapı

| Klasör | İçerik |
| --- | --- |
| `MemoCore/` | Platformdan bağımsız kurallar: `ScriptScene` (Android `Scene`), `OcrText`, `TextColumns`, `ScriptFormats`, `LanguageGuess`, `ScriptLibrary`, `VoiceCast`, `EnsembleRules`. |
| `MemoEverything/App` | `AppModel`: Android `MainActivity` mantığının karşılığı (ekranlar, prova, düzenleme, içe aktarma, eller serbest ilerleme). |
| `MemoEverything/Services` | Cihaz sesi (AVSpeechSynthesizer), konuşma tanıma (Speech), yapay zekâ sesi (sherpa-onnx), App Store aboneliği (StoreKit 2), PDF/fotoğraf okuma (PDFKit + Vision), ekip provası (Firebase). |
| `MemoEverything/UI` | SwiftUI ekranları; renkler, ölçüler ve ikonlar Android ile aynı. |
| `MemoEverything/Resources` | `scripts/convert_resources.py` ile Android `res/` klasöründen üretilen çeviriler ve vektör ikonlar. |

Android metinleri değişince çevirileri yeniden üret:

```sh
python3 ios/scripts/convert_resources.py
```

Google Play, Android yedeklemesi ve Filmler klasörü gibi platforma özgü ifadeler `scripts/ios_strings.json` içinde iOS karşılıklarıyla 7 dilde tutulur.

## Android'den farkları

| Android | iOS |
| --- | --- |
| Google Play Billing, `memo_premium` (monthly/yearly) | StoreKit 2, App Store Connect'te bir abonelik grubunda `memo_premium_monthly` ve `memo_premium_yearly` |
| ML Kit metin tanıma | Apple Vision (cihazda, çevrimdışı) |
| PdfBox | PDFKit |
| Android TextToSpeech | AVSpeechSynthesizer; ses seçimi **Ayarlar → Prova sesi** |
| SpeechRecognizer | Speech çerçevesi; destekleniyorsa cihaz içi tanıma |
| Sistem indirme yöneticisi | Arka plan `URLSession` (uygulama kapalıyken de iner) |
| Filmler › Memo Everything | Fotoğraflar › Memo Everything albümü |
| Android otomatik yedekleme | iCloud/bilgisayar yedeği; yapay zekâ ses modeli yedeğe girmez |

Premium test anahtarı (**Ayarlar → Premium → Test modu**) yalnızca Debug derlemelerinde görünür.

## Ekip provası (Firebase)

Android ile aynı Firebase projesini ve `firestore.rules` kurallarını kullanır.

1. Firebase konsolunda projeye `com.memoeverything.app` paket kimliğiyle bir **iOS uygulaması** ekle.
2. İndirilen `GoogleService-Info.plist` dosyasını `ios/` klasörüne koy (`ios/GoogleService-Info.plist`).
3. Google ile giriş için dosyadaki `REVERSED_CLIENT_ID` değerini `project.yml` içindeki `GOOGLE_REVERSED_CLIENT_ID` ayarına yaz.
4. `xcodegen generate` ile projeyi yeniden üret.

Dosya yoksa uygulama derlenir ve ekip provası kartı gizlenir. Davet linkleri (`memoeverything://join/KOD` ve `https://caglar-yildiz.github.io/Script-prep/join/?c=KOD`) iPhone'da da uygulamayı açar.

## App Store

- Paket kimliği: `com.memoeverything.app`, en düşük iOS 16.
- Arşiv: Xcode'da **Product → Archive**, sonra **Distribute App**.
- İzin açıklamaları (mikrofon, konuşma tanıma, kamera, Fotoğraflar) 7 dilde `*.lproj/InfoPlist.strings` dosyalarında.
- Gizlilik politikası Android ile aynı: https://caglar-yildiz.github.io/Script-prep/
