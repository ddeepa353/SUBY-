# SUBY secure calling backend

This repository now contains a Vercel serverless endpoint at `POST /api/livekit-token`.
It does not contain any LiveKit or Firebase secrets.

## Required Vercel environment variables

Set these in Vercel Project Settings -> Environment Variables:

- `LIVEKIT_URL`: `wss://suby-paecac5h.livekit.cloud`
- `LIVEKIT_API_KEY`: the API key created in LiveKit Cloud
- `LIVEKIT_API_SECRET`: the matching API secret (never commit it)
- `FIREBASE_SERVICE_ACCOUNT_JSON`: the full Firebase Admin service-account JSON, stored only as a Vercel secret

Deploy after setting the variables.

## Endpoint contract

Send an authenticated Firebase ID token:

```http
POST /api/livekit-token
Authorization: Bearer <Firebase ID token>
Content-Type: application/json

{"roomName":"call_abc123"}
```

Success response:

```json
{
  "serverUrl": "wss://...",
  "participantToken": "<short-lived LiveKit JWT>",
  "expiresIn": 600
}
```

The participant identity comes from the verified Firebase UID, never from request input. The endpoint only accepts a restricted room-name format and issues a 10-minute token.

## Important

- Configure Firebase Authentication in the Android app before using this endpoint.
- Create a Firebase project and a service account; store its JSON only in Vercel environment variables.
- Do not put the LiveKit API secret or Firebase service-account JSON in Android assets, APK files, or this public repository.
- This endpoint is not a complete calling/payment product. The Android client still needs to request this token and connect using the LiveKit Android SDK. Wallet, payments, gifts, host approval and payouts remain unimplemented.
- Add rate limiting and server-side authorization (for example, verified host status) before public launch.
