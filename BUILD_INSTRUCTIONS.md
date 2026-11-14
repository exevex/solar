# Solar Sandbox — APK Build Instructions (GitHub Actions & Local)

## Option A: Automated APK Builds on GitHub Actions

This repository includes `.github/workflows/android-apk.yml`, which automatically builds APKs, runs unit/Robolectric tests and Android lint, and verifies APK signatures on every push to `main`/`master`, pull request, `v*` tag, or manual **Run workflow** trigger.

### 1. Immediate Debug APK Builds (Zero Setup)
1. Push this repository to GitHub.
2. Open the **Actions** tab → select **Build Android APK**.
3. Download **`Solar-Sandbox-debug-apk`** from a **successful** workflow run's **Artifacts** section. It contains `app-debug.apk`, its SHA-256 checksum, signing verification, and package metadata. Test XML/HTML and lint reports are in **`Solar-Sandbox-build-reports`**.

No API keys, `.env`, Firebase configuration, or signing secrets are required for a debug APK. The checked-in Gradle wrapper selects Gradle 9.3.1, and CI installs API 36.1 and Build-Tools 36.0.0 explicitly.

#### Debug signing and in-place updates

- If `debug.keystore` exists in the repository root (for example, in AI Studio), the app retains that signing identity.
- Otherwise Gradle uses its standard debug signer and generates `~/.android/debug.keystore` automatically. Fresh CI runners can generate different certificates, so these builds are installable but are **not guaranteed to update a previously installed build**.
- For stable CI debug updates, keep your existing debug keystore securely and store its base64 bytes in the optional Actions secret `DEBUG_KEYSTORE_BASE64`. It must use alias `androiddebugkey` and the standard `android` store/key passwords. CI restores it to the ignored root `debug.keystore`; never commit the key or its base64 contents.
- An APK with a different certificate cannot replace an installed app. Export any saved simulations before choosing to uninstall; uninstalling deletes the app's data. Production releases should use the separate release keystore below.

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

## Option B: Local Builds

### Prerequisites
- **JDK 17+** (CI uses JDK 21; includes `keytool` and `jarsigner`)
- **Android SDK** (API **36.1**, Build-Tools **36.0.0**) with `ANDROID_HOME` or `ANDROID_SDK_ROOT` configured, or `sdk.dir` in ignored `local.properties`
- The included **Gradle 9.3.1 wrapper**; no global Gradle installation is needed

Install the exact SDK packages after accepting the Android SDK licenses:

```bash
sdkmanager "platform-tools" "build-tools;36.0.0" "platforms;android-36.1"
```

### Build and Check a Debug APK (No Signing Setup)

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows use `gradlew.bat` instead. The signed debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

The standard Android SDK host build-tools are not native Termux/Android binaries. On a phone, use the GitHub Actions workflow above rather than treating a successful Java/Gradle preflight as a completed APK build.

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
./gradlew :app:testDebugUnitTest
```

### Step 4: Build the Signed Release APK (or AAB)

```bash
./gradlew :app:assembleRelease
```

Output locations:
- Signed APK: `app/build/outputs/apk/release/app-release.apk`
- Signed App Bundle: `app/build/outputs/bundle/release/app-release.aab` (`./gradlew :app:bundleRelease`)

### Step 5: Verify the APK Signature

```bash
"${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/36.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```
