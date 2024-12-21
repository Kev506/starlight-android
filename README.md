# Starlight-Android
Gamestream client for Android TV

![Starlight](https://starlight-game-streaming.com/gallery_gen/240a46036ccec336e7df544b9d444745_1408x792_fit.png?ts=1734105590)

Starlight was created by ACSDigital as a project inspired by Moonlight Game Streaming client for Android. 

Starlight for Android TV will allow you to stream your full collection of games from your Windows PC to your Android/Google TV device.
The code for Starlight will be available here once released under the GPLv3 license. We welcome contributions once available.

[Starlight for Android](https://starlight-game-streaming.com) is an open source client implementation of Moonlight for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).


## Downloads
* COMING SOON


## Building
* Install [Android Studio](https://developer.android.com/studio/install) and the Android NDK, ensure the correct  NDK version is installed.
* Run ‘git submodule update --init --recursive’ from within starlight-android/
* In starlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* For testing locally on devices, create a keystore.properties file in the project root, once the app bundle is signed using [Sign your app](https://developer.android.com/studio/publish/app-signing), you then enter the details in the keystore file as follows:
  * storeFile = "location of your key jks file"
  * storePassword = "your password"
  * keyAlias = "your alias"
  * keyPassword = "your key password"
  * The build.gradle is already configured to retreive the values
* You will need to register the app using [Firebase](https://firebase.google.com/docs/android/setup), as the configuration file is not included.
  * Once setup download the configuration file and place it in the projects app root
  * Everything is already set to connect to the Firebase API so there is nothing more to do
  * If you are unsure about using Firebase contact [Support](https://starlight-game-streaming.com/Contact)
* Build the APK using Android Studio or gradle
* As the app bundle is signed you can deploy and test on local devices.


## Authors
Starlight Developer:
* [Kevin Andrews](https://github.com/kev506)

## Thanks To
Without Moonlight for Android Starlight would not have been possible.

Moonlight Developers:
* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was started as a project at [MHacks](http://mhacks.org).
