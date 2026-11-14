# Solar Sandbox — Signed Release APK Build Instructions

## Prerequisites
- **JDK 17+** (includes `keytool` and `jarsigner`)
- **Android SDK** (`compileSdk 36`, Build-Tools 35/36) with `ANDROID_SDK_ROOT` or `ANDROID_HOME` configured
- **Gradle 8.x / 9.x** (already configured in the project)

---

## Step 1: Generate a Release Signing Keystore

If you do not already have an upload/release keystore, generate `my-upload-key.jks` in the project root directory using `keytool`:

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

When prompted, enter a strong keystore password, key password, and your certificate details (e.g., CN=Solar Sandbox).

> **Important:** Keep `my-upload-key.jks` and your passwords secure and never commit production keystores to public source control.

---

## Step 2: Export Signing Environment Variables

The project's `app/build.gradle.kts` `signingConfigs.create("release")` reads the keystore path and credentials from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) so secrets are never hardcoded:

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_password"
```

---

## Step 3: Run Unit & Physics Verification Tests

```bash
gradle :app:testDebugUnitTest
```

---

## Step 4: Build the Signed Release APK

```bash
gradle :app:assembleRelease
```

The signed release APK will be generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

### Optional: Build a Signed Android App Bundle (.aab) for Google Play

```bash
gradle :app:bundleRelease
```

Output location:

```text
app/build/outputs/bundle/release/app-release.aab
```

---

## Step 5: Verify the APK Signature

Using Android SDK `apksigner` (located in `$ANDROID_SDK_ROOT/build-tools/<version>/apksigner`):

```bash
$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

To install directly onto a connected Android device via ADB:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
