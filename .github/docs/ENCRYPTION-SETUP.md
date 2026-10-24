## 🔐 Local Environment & Secret Key Setup

To protect server credentials and infrastructure, this project utilizes a secure **XXTEA native encryption architecture** handled by the JNI (`NativeKeyLoader`). 

Production keys are never stored in the repository. As a contributor, you must host your own configuration payloads on your server and configure your local app to read them.

### 🛠️ Step 1: Generate Your Encrypted Payloads
Before uploading configurations to your server, you need to use `NativeKeyLoader` to encrypt your raw credentials locally.

1. In Android Studio, open any file or create a temporary Kotlin Scratch file (`File` > `New` > `Scratch File`).
2. Temporarily inject your raw credentials using the following structure:

```kotlin
// 1. Define your FTP Credentials
val rawFtpConfig = """
    FTP_SERVER=your_ftp_host
    FTP_SERVER_USER=your_ftp_username
    FTP_SERVER_ID=your_ftp_password
    FTP_DIRECTORY=your_ftp_directory
""".trimIndent()

// Encrypt for FTP
val encryptedFtp = NativeKeyLoader().encryptPayload(rawFtpConfig)
android.util.Log.d("DEVELOPER_SETUP", "Encrypted FTP Payload:\n$encryptedFtp")

// 2. Define your IGDB Credentials
val rawIgdbConfig = """
    IGDB_CLIENT_ID=your_igdb_client_id
    IGDB_CLIENT_SECRET=your_igdb_secret
""".trimIndent()

// Encrypt for IGDB
val encryptedIgdb = NativeKeyLoader().encryptPayload(rawIgdbConfig)
android.util.Log.d("DEVELOPER_SETUP", "Encrypted IGDB Payload:\n$encryptedIgdb")
```

3. Run the application in **Debug mode**.
4. Open the **Logcat** tab in Android Studio, filter for `DEVELOPER_SETUP`, and copy the resulting space-separated integer payload strings.
5. ⚠️ **CRITICAL:** Revert any temporary changes you made to the codebase. Ensure your raw credentials are completely wiped from your local files before proceeding.

---

### 🌐 Step 2: Deploy to Your Remote Server
Your local app instance expects to find these exact encrypted strings hosted securely on your server endpoints.

1. Create two separate text files on your web server (e.g. `ftp-data.config` and `igdb-data.config`).
2. Paste the **encrypted payload string** (the space-separated numbers) exactly as it appeared in Logcat into those files.
3. Ensure your server serves these raw text files over HTTPS (`https://`).

---

### ⚙️ Step 3: Point the App to Your Server
Finally, update the server endpoints in your local project so the app fetches payloads from your custom server:

1. Update the configuration download URLs in `LimeLog.kt line 282` (for FTP logs) and `IgdbRepository.kt line 480` (for IGDB).
2. When the app runs, it will hit your server, pull down the encrypted blobs, and JNI will decrypt them seamlessly in memory.

⚠️ **Security Reminder:** Never commit your raw server credentials or encrypted payload strings to GitHub.


