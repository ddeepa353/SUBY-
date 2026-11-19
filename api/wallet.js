const admin = require("firebase-admin");

function getAdmin() {
  if (!admin.apps.length) {
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if (!raw) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
    admin.initializeApp({ credential: admin.credential.cert(JSON.parse(raw)) });
  }
  return admin;
}

const GIFT_PRICES = new Set([50, 100, 200, 500, 1000, 2000, 5000, 10000]);
const CALL_RATES = { audio: 50, video: 100 };

module.exports = async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store");
  if (req.method !== "POST") return res.status(405).json({ error: "POST required" });
  try {
    const match = (req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
    if (!match) return res.status(401).json({ error: "Sign-in required" });
    const adminApp = getAdmin();
    const decoded = await adminApp.auth().verifyIdToken(match[1]);
    if (decoded.email_verified !== true) return res.status(403).json({ error: "Verify your email first" });

    const db = adminApp.firestore();
    const body = req.body || {};
    const action = String(body.action || "");
    const uid = String(decoded.uid);
    const userRef = db.collection("users").doc(uid);
    const userSnap = await userRef.get();
    if (!userSnap.exists) return res.status(404).json({ error: "User profile not found" });

    if (action === "balance") {
      const walletSnap = await db.collection("walletBalances").doc(uid).get();
      return res.status(200).json({ kiss: walletSnap.exists ? Number(walletSnap.data().kiss || 0) : 0 });
    }

    if (action === "gift") {
      const hostUid = String(body.hostUid || "");
      const kiss = Number(body.kiss);
      if (!hostUid || hostUid === uid || !GIFT_PRICES.has(kiss)) return res.status(400).json({ error: "Invalid host or gift amount" });
      const hostRef = db.collection("users").doc(hostUid);
      const senderWallet = db.collection("walletBalances").doc(uid);
      const hostWallet = db.collection("walletBalances").doc(hostUid);
      const txRef = db.collection("giftTransactions").doc();
      const result = await db.runTransaction(async tx => {
        const [hostSnap, senderSnap, hostWalletSnap, senderWalletSnap] = await Promise.all([
          tx.get(hostRef), tx.get(userRef), tx.get(hostWallet), tx.get(senderWallet)
        ]);
        if (!hostSnap.exists || hostSnap.data().role !== "host" || hostSnap.data().status !== "active") throw new Error("Host is not approved");
        const current = senderWalletSnap.exists ? Number(senderWalletSnap.data().kiss || 0) : 0;
        if (current < kiss) throw new Error("INSUFFICIENT_KISS");
        const hostCurrent = hostWalletSnap.exists ? Number(hostWalletSnap.data().kiss || 0) : 0;
        tx.set(senderWallet, { uid, kiss: current - kiss, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
        tx.set(hostWallet, { uid: hostUid, kiss: hostCurrent + kiss, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
        tx.create(txRef, { type: "gift", uid, hostUid, kiss, hostCreditKiss: kiss, status: "completed", createdAt: adminApp.firestore.FieldValue.serverTimestamp() });
        return { balance: current - kiss, hostBalance: hostCurrent + kiss };
      });
      return res.status(200).json({ ok: true, ...result, hostCreditKiss: kiss });
    }

    if (action === "call-start") {
      const hostUid = String(body.hostUid || "");
      const mode = String(body.mode || "");
      const sessionId = String(body.sessionId || "");
      if (!hostUid || hostUid === uid || !CALL_RATES[mode] || !/^[A-Za-z0-9_-]{12,80}$/.test(sessionId)) return res.status(400).json({ error: "Invalid call details" });
      const hostRef = db.collection("users").doc(hostUid);
      const callerWallet = db.collection("walletBalances").doc(uid);
      const hostWallet = db.collection("walletBalances").doc(hostUid);
      const sessionRef = db.collection("callSessions").doc(sessionId);
      const result = await db.runTransaction(async tx => {
        const [hostSnap, callerWalletSnap, hostWalletSnap, existing] = await Promise.all([
          tx.get(hostRef), tx.get(callerWallet), tx.get(hostWallet), tx.get(sessionRef)
        ]);
        if (!hostSnap.exists || hostSnap.data().role !== "host" || hostSnap.data().status !== "active") throw new Error("Host is not approved");
        if (existing.exists) throw new Error("Call session already exists");
        const balance = callerWalletSnap.exists ? Number(callerWalletSnap.data().kiss || 0) : 0;
        const rate = CALL_RATES[mode];
        if (balance < rate) throw new Error("INSUFFICIENT_KISS");
        const hostBalance = hostWalletSnap.exists ? Number(hostWalletSnap.data().kiss || 0) : 0;
        tx.set(callerWallet, { uid, kiss: balance - rate, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
        tx.set(hostWallet, { uid: hostUid, kiss: hostBalance + rate, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
        tx.create(sessionRef, { sessionId, callerUid: uid, hostUid, mode, rate, chargedMinutes: 1, status: "active", startedAt: adminApp.firestore.FieldValue.serverTimestamp(), startedAtMs: Date.now(), updatedAt: adminApp.firestore.FieldValue.serverTimestamp() });
        tx.create(db.collection("callTransactions").doc(), { sessionId, uid, hostUid, mode, kiss: rate, minute: 1, status: "completed", createdAt: adminApp.firestore.FieldValue.serverTimestamp() });
        return { balance: balance - rate, hostBalance: hostBalance + rate, chargedKiss: rate, chargedMinutes: 1 };
      });
      return res.status(200).json({ ok: true, ...result });
    }

    if (action === "call-tick" || action === "call-end") {
      const sessionId = String(body.sessionId || "");
      if (!/^[A-Za-z0-9_-]{12,80}$/.test(sessionId)) return res.status(400).json({ error: "Invalid sessionId" });
      const sessionRef = db.collection("callSessions").doc(sessionId);
      const result = await db.runTransaction(async tx => {
        const sessionSnap = await tx.get(sessionRef);
        if (!sessionSnap.exists) throw new Error("Call session not found");
        const session = sessionSnap.data();
        if (session.callerUid !== uid) throw new Error("Not your call session");
        if (session.status !== "active") return { ended: true, chargedKiss: 0 };
        const elapsed = Math.max(0, Date.now() - Number(session.startedAtMs || Date.now()));
        const dueMinutes = Math.floor(elapsed / 60000) + 1;
        const extraMinutes = action === "call-end" ? 0 : Math.max(0, dueMinutes - Number(session.chargedMinutes || 1));
        const extra = extraMinutes * Number(session.rate);
        const callerWallet = db.collection("walletBalances").doc(uid);
        const hostWallet = db.collection("walletBalances").doc(session.hostUid);
        const [callerSnap, hostSnap] = await Promise.all([tx.get(callerWallet), tx.get(hostWallet)]);
        const balance = callerSnap.exists ? Number(callerSnap.data().kiss || 0) : 0;
        if (balance < extra) {
          tx.update(sessionRef, { status: "ended_insufficient_balance", endedAt: adminApp.firestore.FieldValue.serverTimestamp() });
          return { insufficient: true, balance, chargedKiss: 0 };
        }
        if (extra > 0) {
          const hostBalance = hostSnap.exists ? Number(hostSnap.data().kiss || 0) : 0;
          tx.set(callerWallet, { uid, kiss: balance - extra, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
          tx.set(hostWallet, { uid: session.hostUid, kiss: hostBalance + extra, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() }, { merge: true });
          for (let n = 1; n <= extraMinutes; n++) {
            tx.create(db.collection("callTransactions").doc(), { sessionId, uid, hostUid: session.hostUid, mode: session.mode, kiss: session.rate, minute: Number(session.chargedMinutes || 1) + n, status: "completed", createdAt: adminApp.firestore.FieldValue.serverTimestamp() });
          }
          tx.update(sessionRef, { chargedMinutes: Number(session.chargedMinutes || 1) + extraMinutes, updatedAt: adminApp.firestore.FieldValue.serverTimestamp() });
        }
        if (action === "call-end") tx.update(sessionRef, { status: "ended", endedAt: adminApp.firestore.FieldValue.serverTimestamp() });
        return { balance: balance - extra, chargedKiss: extra, chargedMinutes: extraMinutes, ended: action === "call-end" };
      });
      return res.status(200).json({ ok: true, ...result });
    }

    return res.status(400).json({ error: "Unknown wallet action" });
  } catch (error) {
    const message = error.message || "Wallet operation failed";
    if (message === "INSUFFICIENT_KISS") return res.status(402).json({ error: "Insufficient Kiss balance", code: message });
    if (message === "Host is not approved") return res.status(403).json({ error: message });
    console.error("SUBY wallet API:", message);
    return res.status(400).json({ error: message });
  }
};
