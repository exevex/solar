# Solar Sandbox — APK Build Instructions (GitHub Actions & Local)

## Option A: Automated APK Builds on GitHub Actions

This repository includes `.github/workflows/android-apk.yml`, which automatically runs tests and builds APKs on every push to `main`/`master`, pull request, `v*` tag, or manual **Run workflow** trigger.

### 1. Immediate Debug APK Builds (Zero Setup)
1. Push this repository to GitHub.
2. Open the **Actions** tab → select **Build Android APK**.
3. Download **`Solar-Sandbox-debug-apk`** from the workflow run's **Artifacts** section.

### 2. Enable Signed Release APKs & AABs on GitHub
To have GitHub Actions also build a production-signed `app-release.apk` and `app-release.aab` (and attach them to GitHub Releases on `v*` tags):

1. Generate your keystore locally (see Step 1 below).
2. Base64-encode the keystore:
   ```bash
   base64 -w 0 my-upload-key.jks
   ```
   *(On macOS use `base64 -i my-upload-key.jks | pbcopy`)*
3. In your GitHub repository, go to **Settings → Secrets and variables → Actions → New repository secret** and add:
   - `KEYSTORE_BASE64` — the single-line base64 string of `my-upload-key.jks`
   - `STORE_PASSWORD` — your keystore password
   - `KEY_PASSWORD` — your key password for alias `upload`
4. Re-run the workflow or push a commit/tag (`git tag v1.0.0 && git push origin v1.0.0`).

---

## Option B: Local Signed Release APK Build

### Prerequisites
- **JDK 17+** (includes `keytool` and `jarsigner`)
- **Android SDK** (`compileSdk 36`, Build-Tools 35/36) with `ANDROID_SDK_ROOT` or `ANDROID_HOME` configured
- **Gradle 9.3.1**

### Step 1: Generate a Release Signing Keystore

Generate `my-upload-key.jks` in the project root directory using `keytool`:

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

> **Important:** Keep `my-upload-key.jks` and your passwords secure and never commit production keystores to public source control.

### Step 2: Export Signing Environment Variables

The project's `app/build.gradle.kts` `signingConfigs.create("release")` reads the keystore path and credentials from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`):

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_password"
```

### Step 3: Run Unit & Physics Verification Tests

```bash
gradle :app:testDebugUnitTest
```

### Step 4: Build the Signed Release APK (or AAB)

```bash
gradle :app:assembleRelease
```

Output locations:
- Signed APK: `app/build/outputs/apk/release/app-release.apk`
- Signed App Bundle: `app/build/outputs/bundle/release/app-release.aab` (`gradle :app:bundleRelease`)

### Step 5: Verify the APK Signature

```bash
$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```
