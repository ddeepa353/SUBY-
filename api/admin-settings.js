const admin = require("firebase-admin");
function getAdmin(){if(!admin.apps.length){const raw=process.env.FIREBASE_SERVICE_ACCOUNT_JSON;if(!raw)throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured");admin.initializeApp({credential:admin.credential.cert(JSON.parse(raw))});}return admin;}
module.exports=async function(req,res){
 res.setHeader("Cache-Control","no-store");
 if(!["GET","POST"].includes(req.method)){res.setHeader("Allow","GET, POST");return res.status(405).json({error:"Method not allowed"});}
 try{
  const h=req.headers.authorization||"",m=h.match(/^Bearer\\s+(.+)$/i);if(!m)return res.status(401).json({error:"Sign-in required"});
  const a=getAdmin(),decoded=await a.auth().verifyIdToken(m[1]);
  const email=String(decoded.email||"").toLowerCase();
  if(email!=="thakurpriya838395@gmail.com"||decoded.email_verified!==true)return res.status(403).json({error:"Owner access denied. Use the verified owner Gmail."});
  const ref=a.firestore().collection("adminSettings").doc("payment");
  if(req.method==="GET"){const s=await ref.get();return res.status(200).json(s.exists?s.data():{});}
  const b=req.body||{},upiId=String(b.upiId||"").trim(),whatsapp=String(b.whatsapp||"").replace(/\\D/g,""),instructions=String(b.instructions||"").slice(0,3000),qrDataUrl=String(b.qrDataUrl||"");
  if(upiId&&!/^[A-Za-z0-9._-]{2,}@[A-Za-z0-9.-]{2,}$/.test(upiId))return res.status(400).json({error:"Invalid UPI ID"});
  if(whatsapp.length<10||whatsapp.length>15)return res.status(400).json({error:"Invalid WhatsApp number"});
  if(qrDataUrl&&(!/^data:image\\/(png|jpeg|webp);base64,/.test(qrDataUrl)||qrDataUrl.length>620000))return res.status(400).json({error:"QR must be PNG/JPEG/WebP and under 450 KB"});
  await ref.set({upiId,whatsapp,instructions,qrDataUrl,updatedAt:a.firestore.FieldValue.serverTimestamp(),updatedBy:email},{merge:true});
  return res.status(200).json({ok:true});
 }catch(e){console.error("admin-settings:",e.message);return res.status(503).json({error:e.message.includes("not configured")?e.message:"Admin settings service unavailable"});}
};