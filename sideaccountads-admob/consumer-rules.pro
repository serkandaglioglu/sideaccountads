# Uygulamanın R8'i için kurallar.
# Core, mediator sınıfını manifest meta-data'sındaki adıyla yükler ve no-arg constructor ile yaratır.
-keep class com.sideaccountapps.ads.admob.AdMobMediator { public <init>(); }
# GMA, Google adaptörleri ve ağ SDK'ları kendi kurallarını AAR'larında getirir; burada ek kural gerekmez.
