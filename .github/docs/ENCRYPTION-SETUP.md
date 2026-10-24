## 🔐 Local Environment & Secret Key Setup

To protect server credentials and infrastructure, this project utilizes a secure **XXTEA native encryption architecture** handled by the JNI (`NativeKeyLoader`). 

Production keys are never stored in the repository. As a contributor, you must host your own configuration payloads on your server and configure your local app to read them.

### 🛠️ Step 1: Generate Your Encrypted Payloads
Before uploading configurations to your server, you need to use the `NativeKeyLoader` to encrypt your raw credentials locally.

1. In Android Studio, open **`RetrofitClient.kt`** or create a temporary Kotlin Scratch file (`File` > `New` > `Scratch File`).
2. Temporarily inject your raw credentials into a debug block or local unit test using the following structure:

```kotlin
// 1. Define your FTP Credentials
val rawFtpConfig = """
    FTP_SERVER=your_ftp_host
    FTP_SERVER_USER=your_ftp_username
    FTP_SERVER_ID=your_ftp_password
    FTP_DIRECTORY=your_ftp_directory
""".trimIndent()

// Encrypt for FTP
val encryptedFtp = NativeKeyLoader().encryptFtpPayload(rawFtpConfig)
android.util.Log.d("DEVELOPER_SETUP", "Encrypted FTP Payload:\n\$encryptedFtp")

// 2. Define your IGDB Credentials
val rawIgdbConfig = """
    IGDB_CLIENT_ID=your_igdb_client_id
    IGDB_CLIENT_SECRET=your_igdb_secret
""".trimIndent()

// Encrypt for IGDB
val encryptedIgdb = NativeKeyLoader().encryptIgdbPayload(rawIgdbConfig)
android.util.Log.d("DEVELOPER_SETUP", "Encrypted IGDB Payload:\n\$encryptedIgdb")
```

3. Run the application in **Debug mode**.
4. Open the **Logcat** tab in Android Studio, filter for `DEVELOPER_SETUP`, and copy the resulting jumbled, encrypted strings.
5. ⚠️ **CRITICAL:** Revert any changes you made to the codebase. Ensure your raw credentials are completely wiped from your local files before proceeding.

---

### 🌐 Step 2: Deploy to Your Remote Server
Your official app instance expects to find these exact encrypted strings hosted securely on your personal server endpoints.

1. Create two separate text/config files on your server (or your preferred endpoint API structures).
2. Paste the **encrypted payload string** exactly as it appeared in Logcat into those files.
3. Ensure your server serves these raw text files over a secure connection (`https://`).

---

### ⚙️ Step 3: Point the App to Your Server
Finally, you must tell your local build where to fetch these encrypted files.

1. Open your local `local.properties` file (which is ignored by Git).
2. Add your custom server endpoints:
   ```properties
   DEVELOPER_FTP_URL="https://yourserver.com"
   DEVELOPER_IGDB_URL="https://yourserver.com"
   ```
3. Sync Gradle. The project's build config will feed these URLs into your local `RetrofitClient` instance. When the app runs, it will hit your server, pull down the encrypted blobs, and your local JNI layer will seamlessly pass back the decrypted configurations in memory.

⚠️ **Security Reminder:** Never commit your server URLs or raw payload strings to GitHub. If you accidentally modify `RetrofitClient.kt` with hardcoded credentials, the automated CI/CD pipeline will reject your Pull Request.
