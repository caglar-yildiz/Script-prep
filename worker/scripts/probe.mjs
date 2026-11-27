// Tries the ways of giving Gemini 3.8 TTS a delivery and transcribes each result back, to see
// which directions are followed and which are spoken aloud. Prints no key. Run on CI.
const key = process.env.GEMINI_API_KEY;
const BASE = "https://generativelanguage.googleapis.com/v1beta/";
const headers = { "Content-Type": "application/json", "X-goog-api-key": key };
const MODEL = "gemini-3.8-flash-lite-tts";
const LINE = "Sen yorgunsun! Ben üç saattir bekliyorum.";

async function call(path, body) {
  const response = await fetch(BASE + path, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: response.status, text, json };
}

function findAudio(value) {
  if (!value || typeof value !== "object") return null;
  if (typeof value.data === "string" && value.data.length > 1000) return { data: value.data, mime: value.mimeType ?? value.mime_type };
  for (const v of Object.values(value)) { const f = findAudio(v); if (f) return f; }
  return null;
}

function shape(value, depth = 0) {
  if (Array.isArray(value)) return [value.length ? shape(value[0], depth + 1) : "empty"];
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, depth > 5 ? "…" : shape(v, depth + 1)]));
  }
  return typeof value === "string" && value.length > 80 ? `string(${value.length})` : value;
}

async function transcribe(audio) {
  const r = await call("models/gemini-3.5-flash-lite:generateContent", {
    contents: [{ parts: [
      { inlineData: { mimeType: audio.mime?.includes("wav") ? "audio/wav" : "audio/wav", data: audio.data } },
      { text: "Line 1: write exactly the words that are spoken, nothing else - no annotations, no brackets. If a word such as 'whispering', 'pause' or 'long' is actually spoken aloud, write it. Line 2: describe in English how each sentence is delivered (volume, emotion, pace) and whether the speaker sounds male or female." },
    ] }],
  });
  return r.json?.candidates?.[0]?.content?.parts?.map((p) => p.text).join("") ?? `transcribe failed ${r.status} ${r.text.slice(0, 300)}`;
}

const interaction = (text, style, voice = "Kore") => call("interactions", {
  model: MODEL,
  input: [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style }] }] }],
  response_format: { type: "audio" },
  generation_config: { speech_config: [{ voice }] },
});
const STYLE = "furious and fast: shouting the first sentence, then a long pause, then whispering the second bitterly";
const variants = {
  styleOnlyPauseTag: () => interaction("Sen yorgunsun! <long pause> Ben üç saattir bekliyorum.", STYLE),
  styleWithWhisperTag: () => interaction("Sen yorgunsun! <long pause> <whispering> Ben üç saattir bekliyorum.", STYLE),
  calmCharon: () => interaction("Bağırma. Komşular duyacak.", "tired, quiet and slow, trying to calm her down", "Charon"),
};

for (const [name, run] of Object.entries(variants)) {
  const started = Date.now();
  const r = await run();
  console.log(`\n=== ${name}: HTTP ${r.status} in ${Date.now() - started} ms`);
  if (r.status !== 200) { console.log(r.text.slice(0, 600)); continue; }
  console.log("shape:", JSON.stringify(shape(r.json)));
  const audio = findAudio(r.json);
  if (!audio) { console.log("no audio found"); continue; }
  console.log("mime:", audio.mime, "base64 chars:", audio.data.length);
  console.log("heard:", await transcribe(audio));
}
