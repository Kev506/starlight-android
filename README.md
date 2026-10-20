# Starlight-Android
Gamestream client for Android TV

![Starlight](https://starlight-stream.com/gallery/1000000213.png)
![Starlight](https://starlight-stream.com/gallery/Screenshot_20261019113834.png)
![Starlight](https://starlight-stream.com/gallery/Screenshot_20240525_103001.png)
![Starlight](https://starlight-stream.com/gallery/1000000214.png)
![Starlight](https://starlight-stream.com/gallery/Screenshot_20261019112354.png)

Starlight was created by ACSDigital as a project inspired by Moonlight Game Streaming client for Android, 
Starlight has been refactored and developed in full [Compose for TV](https://developer.android.com/training/tv/playback/compose).

Starlight for Android/Google TV will allow you to stream your full collection of games from your Windows PC to your Android/Google TV device.
The code for Starlight is available here, released under the GPLv3 license. We welcome contributions.

[Starlight for Android](https://starlight-game-streaming.com) is an open source client implementation of Moonlight for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).


## ✨ Top Features
* Full game details, artwork, HD trailers, and more.
* Supports Android AAudio Super low Latency, with Bluetooth headphone PCM fallback hot swap.
* Supports AC-3 and E-AC-3 5.1 HDMI/eArc Dolby Digital Plus, with Bluetooth headphone PCM fallback hot swap.
* Automatically adds your favorites to the Android TV Home Screen.
* Adds your running title to Watch Next on pause.
* Supports multiple controllers.
* Supports multiple languages.


## 🔗 Downloads
* COMING SOON

## 🛠️ Contribution & Local Build Setup

This project uses **Firebase** for backend services (such as Remote Config / Auth). For security and quota management, the official `google-services.json` file is omitted from this repository. 

To build and run the app locally, you must provide your own Firebase configuration.

### Prerequisites
1. Create a free account on the [Firebase Console](https://google.com).
2. Click **Add Project** and follow the steps to set up a new project.
3. Add an **Android App** to your Firebase project. Ensure the **Package Name** exactly matches the one in our `app/build.gradle` file (`com.yourcompany.yourapp`).

### Configuration Steps
1. Download the `google-services.json` file provided at the end of the Firebase App setup wizard.
2. Place the downloaded `google-services.json` file into the root of your app module directory:
   ```text
   YOUR_PROJECT_ROOT/app/google-services.json
   ```
3. This application relies on Remote Config, make sure to add corresponding parameter keys in your own Firebase Console matching the following.

1. IGDB: = clientid
2. IGDB: = clientsecret
3. Your FTP SERVER: = server
4. Your FTP Directory: = directory
5. Your FTP Usename: = username
6. Your FTP Password: = directory

You can now move on to the next step and build and run the project.

## 🏗️ Building
* Install [Android Studio](https://developer.android.com/studio/install) and the Android NDK, ensure the latest [NDK version](https://developer.android.com/ndk/downloads) is installed.
* Run ‘git submodule update --init --recursive’ from within starlight-android/
* In starlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* For testing locally on devices, create a keystore.properties file in the project root, once the app bundle is signed using [Sign your app](https://developer.android.com/studio/publish/app-signing), you then enter the details in the keystore file as follows:
    * storeFile = "location of your key jks file"
    * storePassword = "your password"
    * keyAlias = "your alias"
    * keyPassword = "your key password"
    * The build.gradle is already configured to retrieve the values
* Build the APK using Android Studio or gradle
* As the app bundle is signed you can deploy and test on local devices.

## 📦 Project Architecture

This project is built using a modern, scalable multi-module architecture:

* **`Starlight Android`** - The primary Jetpack Compose TV application.
* **[Video Player](https://central.sonatype.com/artifact/io.github.kev506/compose-custom-youtube-player) - Our standalone custom Compose YouTube player library with persistent subtitle engine and language controls (published on Maven Central).


## ᝰ🖋️ Authors
Starlight Developer:
* [Kevin Andrews](https://github.com/kev506)

## 🙏 Thanks To
Without Moonlight for Android Starlight would not have been possible.

Moonlight Developers:
* [Cameron Gutman](https://github.com/cgutman)
* [Diego Waxemberg](https://github.com/dwaxemberg)
* [Aaron Neyer](https://github.com/Aaronneyer)
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was started as a project at [MHacks](http://mhacks.org).
