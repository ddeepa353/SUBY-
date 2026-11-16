const { AccessToken } = require("livekit-server-sdk");
const admin = require("firebase-admin");

function getFirebaseAdmin() {
  if (!admin.apps.length) {
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if (!raw) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
    admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(raw))
    });
  }
  return admin;
}

module.exports = async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store");
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }

  try {
    const authHeader = req.headers.authorization || "";
    const match = authHeader.match(/^Bearer\s+(.+)$/i);
    if (!match) return res.status(401).json({ error: "Firebase sign-in required" });

    const decoded = await getFirebaseAdmin().auth().verifyIdToken(match[1]);
    const roomName = typeof req.body?.roomName === "string" ? req.body.roomName.trim() : "";
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(roomName)) {
      return res.status(400).json({ error: "Invalid roomName" });
    }

    const livekitUrl = process.env.LIVEKIT_URL;
    const apiKey = process.env.LIVEKIT_API_KEY;
    const apiSecret = process.env.LIVEKIT_API_SECRET;
    if (!livekitUrl || !apiKey || !apiSecret) {
      return res.status(503).json({ error: "LiveKit server settings are incomplete" });
    }

    const identity = String(decoded.uid);
    const token = new AccessToken(apiKey, apiSecret, {
      identity,
      ttl: "10m",
      metadata: JSON.stringify({ role: decoded.role || "user" })
    });
    token.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true
    });

    return res.status(200).json({
      serverUrl: livekitUrl,
      participantToken: await token.toJwt(),
      expiresIn: 600
    });
  } catch (error) {
    console.error("LiveKit token error:", error.message);
    return res.status(401).json({ error: "Could not issue call token" });
  }
};
