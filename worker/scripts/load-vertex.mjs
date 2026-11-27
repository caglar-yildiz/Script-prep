// Sends 120 short speech requests through Google Cloud to see whether a daily or per-minute limit
// stops them. Prints counts only. Run on CI.
import { createSign } from "node:crypto";

const account = JSON.parse(process.env.GCP_SERVICE_ACCOUNT_JSON || "{}");
const b64 = (v) => Buffer.from(v).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const now = Math.floor(Date.now() / 1000);
const head = b64(JSON.stringify({ alg: "RS256", typ: "JWT" }));
const claims = b64(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/cloud-platform",
  aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }));
const signer = createSign("RSA-SHA256");
signer.update(`${head}.${claims}`);
const tokenAnswer = await (await fetch("https://oauth2.googleapis.com/token", { method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${head}.${claims}.${b64(signer.sign(account.private_key))}` })).json();
const url = `https://aiplatform.googleapis.com/v1/projects/${account.project_id}/locations/global/publishers/google/models/gemini-3.8-flash-lite-tts:generateContent`;
const counts = {};
const errors = new Set();
let n = 0;
const started = Date.now();
async function one(i) {
  const response = await fetch(url, { method: "POST", headers: { Authorization: `Bearer ${tokenAnswer.access_token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: `Deneme ${i}.`, speechMetadata: { style: "calm" } }] }],
      generationConfig: { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } } } }) });
  counts[response.status] = (counts[response.status] ?? 0) + 1;
  if (response.status !== 200) errors.add((await response.text()).replace(/\s+/g, " ").slice(0, 300));
  else await response.arrayBuffer();
}
const queue = Array.from({ length: 120 }, (_, i) => i);
await Promise.all(Array.from({ length: 6 }, async () => { while (queue.length) { await one(queue.shift()); n++; } }));
console.log(`done ${n} in ${Math.round((Date.now() - started) / 1000)} s:`, JSON.stringify(counts));
for (const e of errors) console.log("error:", e);
