const admin = require("firebase-admin");

function getAdmin() {
  if (!admin.apps.length) {
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if (!raw) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
    admin.initializeApp({ credential: admin.credential.cert(JSON.parse(raw)) });
  }
  return admin;
}

module.exports = async function (req, res) {
  res.setHeader("Cache-Control", "no-store");
  const origin = req.headers.origin || "";
  const allowedOrigins = new Set(["https://suby-admin.vercel.app", "https://suby-admin-ddeepa353.vercel.app"]);
  if (allowedOrigins.has(origin)) { res.setHeader("Access-Control-Allow-Origin", origin); res.setHeader("Vary", "Origin"); }
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const match = (req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
    if (!match) return res.status(401).json({ error: "Sign-in required" });
    const dbAdmin = getAdmin();
    const decoded = await dbAdmin.auth().verifyIdToken(match[1]);
    const owner = String(process.env.SUBY_ADMIN_EMAIL || "thakurpriya838395@gmail.com").toLowerCase();
    if (String(decoded.email || "").toLowerCase() !== owner || decoded.email_verified !== true) {
      return res.status(403).json({ error: "Owner access denied" });
    }

    const allowed = new Set(["users", "hosts", "rechargeRequests", "withdrawals", "calls"]);
    const collection = String(req.query.collection || "users");
    if (!allowed.has(collection)) return res.status(400).json({ error: "Unknown data section" });
    const limit = Math.min(Math.max(parseInt(req.query.limit || "50", 10) || 50, 1), 100);
    const snap = await dbAdmin.firestore().collection(collection).limit(limit).get();
    const rows = snap.docs.map(doc => {
      const data = doc.data() || {};
      const safe = {};
      for (const [key, value] of Object.entries(data)) {
        if (/password|token|secret|privatekey|serviceaccount/i.test(key)) continue;
        if (value && typeof value.toDate === "function") safe[key] = value.toDate().toISOString();
        else if (value === null || ["string", "number", "boolean"].includes(typeof value)) safe[key] = value;
        else if (Array.isArray(value)) safe[key] = value.slice(0, 20).map(v => (v && typeof v.toDate === "function") ? v.toDate().toISOString() : v);
        else if (typeof value === "object") safe[key] = "[object]";
      }
      return { id: doc.id, ...safe };
    });
    return res.status(200).json({ collection, count: rows.length, rows });
  } catch (error) {
    console.error("admin-data:", error.message);
    return res.status(503).json({ error: error.message.includes("not configured") ? error.message : "Admin data service unavailable" });
  }
};
