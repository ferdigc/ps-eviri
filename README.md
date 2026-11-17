# PS Çeviri – Aşama 1 (TV alıcısı + telefon eşleşmesi)

1. Android Studio ile `PSCeviri` klasörünü aç (Gradle sync eksik wrapper'ı kendisi kurar).
2. `tv` modülünü TCL Google TV'ye, `phone` modülünü telefona yükle (Run > cihaz seç; TV için adb over Wi-Fi: TV'de Geliştirici seçenekleri > Ağ hata ayıklama).
3. TV'de uygulamayı aç, "Overlay iznini ver". Google TV'de bu ekran yoksa bilgisayardan:
   `adb shell appops set com.example.pstranslate.tv SYSTEM_ALERT_WINDOW allow`
4. TV'de "Altyazı alıcısını başlat". Ekranda 4 haneli kod görünür.
5. Telefonda kodu yaz, "TV'ye bağlan", sonra bir metin yazıp "TV'ye gönder". Metin TV'de (HDMI girişinin üstünde de) altyazı olarak çıkmalı.

Aşama 2: telefonda ekran yakalama + ML Kit OCR + EN→TR çeviri.
Aşama 3: PS5 Remote Play (chiaki tabanlı).
