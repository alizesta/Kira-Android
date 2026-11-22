# Firebase Android setup

The Android app is connected to the Firebase project `kira-4b72f` for application ID `ai.kira.mobile`.

## Included SDKs

- Firebase Analytics
- Firebase Authentication
- Cloud Firestore
- Cloud Storage

The Google Services Gradle plugin reads `app/google-services.json` and generates the Android Firebase resources. Firebase's Android initialization provider initializes the default Firebase app automatically; no manual initialization call is required.

## Console-side requirements

- Enable each Firebase product in the Firebase console before using its APIs.
- Enable the intended Authentication providers. Google sign-in also requires the app signing SHA-1/SHA-256 fingerprints to be registered in Firebase.
- Configure restrictive Firestore and Storage Security Rules before allowing client access. Do not use public read/write rules in production.

Adding the SDKs does not itself implement sign-in, cloud synchronization, or data upload in the app; those application flows must be connected to the corresponding repositories and UI separately.
