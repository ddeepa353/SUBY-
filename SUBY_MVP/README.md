# SUBY Android MVP

## Current build status
This repository builds an Android preview APK. The current screens are a UI prototype; wallet balances, gifts, payouts, host verification, and admin metrics are not connected to a production database or payment gateway.

## Real-time audio/video foundation
- LiveKit Android SDK dependency: `io.livekit:livekit-android:2.30.0`
- Android microphone and camera permissions are declared.
- LiveKit rooms are **not enabled yet**. A production app must request runtime permissions, connect to a room, publish microphone/camera tracks, and render remote participants.
- The app must receive short-lived room tokens from a trusted backend. Never put a LiveKit API secret in the APK, HTML, or public repository.
- Required configuration for the next integration step: LiveKit Cloud project URL and API key/secret stored as private server-side secrets, plus a token endpoint URL. Do not commit credentials.

## Next implementation milestones
1. Implement native LiveKit audio/video room UI and runtime permission handling.
2. Add a secure token service; verify user/host identity and room permissions server-side.
3. Add Firebase Auth/Firestore rules and server-side wallet ledger.
4. Integrate a payment gateway only after business/KYC approval; implement admin-reviewed host payouts.
5. Test on physical Android devices and validate network, camera, microphone, billing, and abuse controls.

## Free preview limitation
A successful Gradle build only proves that the APK compiled. It does not prove that calls, payments, or payouts work. LiveKit Cloud free quotas and any provider signup requirements may change; production traffic can incur charges.
