# Alpha Social

Locked-down Firefox-engine (GeckoView) app: WhatsApp Web, Instagram, Telegram Web + the bundled **Alpha** extension.

## Get the APK
1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab -> **Build APK** -> wait ~10 min -> download artifact **AlphaSocial-apk**.
   (Tag `v1.0` to also get it on the Releases page.)
3. Install on the phone (allow "install unknown apps").

## Change things
- Sites / allowed domains: `app/src/main/java/com/alpha/social/MainActivity.java`
- Extension files: `app/src/main/assets/alpha/` (replace with a new build, keep `id` in manifest)
- Package/app name: `app/build.gradle`, `AndroidManifest.xml`
- 32-bit phones: add `'armeabi-v7a'` to `abiFilters` in `app/build.gradle`
