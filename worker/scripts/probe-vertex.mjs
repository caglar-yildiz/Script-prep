// Tries Gemini speech through Google Cloud (Vertex AI / Agent Platform) with the service account,
// and transcribes each answer back with the AI Studio key. Prints no secrets. Run on CI.
import { createSign } from "node:crypto";

const account = JSON.parse(process.env.GCP_SERVICE_ACCOUNT_JSON || "{}");
const studioKey = process.env.GEMINI_API_KEY;
const project = account.project_id;
console.log("project:", project, "| account:", (account.client_email || "").replace(/^(.{4}).*@/, "$1…@"));

function base64Url(value) {
  return Buffer.from(value).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function accessToken() {
  const now = Math.floor(Date.now() / 1000);
  const head = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/cloud-platform",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }));
  const signer = createSign("RSA-SHA256");
  signer.update(`${head}.${claims}`);
  const jwt = `${head}.${claims}.${base64Url(signer.sign(account.private_key))}`;
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${jwt}`,
  });
  const body = await response.json();
  console.log("token:", response.status, body.error ?? "ok");
  return body.access_token;
}

function audioOf(value) {
  if (!value || typeof value !== "object") return null;
  if (typeof value.data === "string" && value.data.length > 1000) return { data: value.data, mime: value.mimeType ?? value.mime_type ?? "" };
  if (typeof value.audioContent === "string") return { data: value.audioContent, mime: "audio/wav" };
  for (const v of Object.values(value)) { const found = audioOf(v); if (found) return found; }
  return null;
}

function wav(pcm, rate = 24000) {
  const head = Buffer.alloc(44);
  head.write("RIFF", 0); head.writeUInt32LE(36 + pcm.length, 4); head.write("WAVEfmt ", 8);
  head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(rate, 24);
  head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34); head.write("data", 36);
  head.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([head, pcm]);
}

async function heard(audio) {
  let bytes = Buffer.from(audio.data, "base64");
  if (bytes.subarray(0, 4).toString() !== "RIFF") bytes = wav(bytes);
  const r = await fetch("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", {
    method: "POST", headers: { "Content-Type": "application/json", "X-goog-api-key": studioKey },
    body: JSON.stringify({ contents: [{ parts: [
      { inlineData: { mimeType: "audio/wav", data: bytes.toString("base64") } },
      { text: "Line 1: write exactly the words spoken, nothing else. If words like 'whispering', 'pause' or 'furious' are spoken aloud, write them. Line 2: describe in English how each sentence is delivered (volume, emotion, pace) and whether the voice sounds male or female." },
    ] }] }),
  });
  const body = await r.json();
  return body.candidates?.[0]?.content?.parts?.map((p) => p.text).join("") ?? `transcribe failed ${r.status}`;
}

const token = await accessToken();
const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
const TEXT = "Sen yorgunsun! <long pause> Ben üç saattir bekliyorum.";
const STYLE = "furious and fast: shouting the first sentence, then whispering the second bitterly";
const speech = { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } } };

const tries = [];
for (const model of ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"]) {
  for (const location of ["global", "us-central1"]) {
    const host = location === "global" ? "aiplatform.googleapis.com" : `${location}-aiplatform.googleapis.com`;
    const url = `https://${host}/v1/projects/${project}/locations/${location}/publishers/google/models/${model}:generateContent`;
    tries.push([`${model} @ ${location} / speechMetadata`, url,
      { contents: [{ role: "user", parts: [{ text: TEXT, speechMetadata: { style: STYLE } }] }], generationConfig: speech }]);
  }
}
tries.push(["cloud tts gemini-2.5-flash-tts / prompt", "https://texttospeech.googleapis.com/v1/text:synthesize", {
  input: { prompt: STYLE, text: "Sen yorgunsun! [long pause] Ben üç saattir bekliyorum." },
  voice: { languageCode: "tr-TR", name: "Kore", modelName: "gemini-2.5-flash-tts" },
  audioConfig: { audioEncoding: "LINEAR16" },
}]);

for (const [name, url, body] of tries) {
  const started = Date.now();
  const response = await fetch(url, { method: "POST", headers: { ...auth, "x-goog-user-project": project }, body: JSON.stringify(body) });
  const text = await response.text();
  console.log(`\n=== ${name}: HTTP ${response.status} in ${Date.now() - started} ms`);
  let json = null;
  try { json = JSON.parse(text); } catch {}
  const audio = response.status === 200 ? audioOf(json) : null;
  console.log(audio ? `mime ${audio.mime} | heard: ${await heard(audio)}` : text.slice(0, 500));
}
