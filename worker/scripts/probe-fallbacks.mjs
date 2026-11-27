// Checks whether the older speech models follow a delivery and keep it out of the spoken words:
// each answer is transcribed back by a text model. Prints no key. Run on CI.
const key = process.env.GEMINI_API_KEY;
const BASE = "https://generativelanguage.googleapis.com/v1beta/";
const headers = { "Content-Type": "application/json", "X-goog-api-key": key };
const TEXT = "Sen yorgunsun! <long pause> Ben üç saattir bekliyorum.";
const STYLE = "furious and fast: shouting the first sentence, then whispering the second bitterly";

async function call(path, body) {
  const response = await fetch(BASE + path, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await response.text();
  try { return { status: response.status, json: JSON.parse(text), text }; } catch { return { status: response.status, text }; }
}

function audioOf(value) {
  if (!value || typeof value !== "object") return null;
  if (typeof value.data === "string" && value.data.length > 1000) return { data: value.data, mime: value.mimeType ?? value.mime_type ?? "" };
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
  const r = await call("models/gemini-3.5-flash-lite:generateContent", { contents: [{ parts: [
    { inlineData: { mimeType: "audio/wav", data: bytes.toString("base64") } },
    { text: "Line 1: write exactly the words spoken, nothing else. If words like 'whispering', 'pause', 'furious' or 'say' are spoken aloud, write them. Line 2: describe in English how each sentence is delivered (volume, emotion, pace)." },
  ] }] });
  return r.json?.candidates?.[0]?.content?.parts?.map((p) => p.text).join("") ?? `transcribe failed ${r.status}`;
}

for (const model of ["gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"]) {
  const tries = {
    interactions: () => call("interactions", {
      model, input: [{ type: "user_input", content: [{ type: "text", text: TEXT, annotations: [{ type: "speech_metadata", style: STYLE }] }] }],
      response_format: { type: "audio" }, generation_config: { speech_config: [{ voice: "Kore" }] },
    }),
    promptInText: () => call(`models/${model}:generateContent`, {
      contents: [{ parts: [{ text: `Read the line below as an actor in a play, ${STYLE}. Say only the line.\n\n${TEXT.replace(" <long pause>", "...")}` }] }],
      generationConfig: { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } } },
    }),
  };
  for (const [name, run] of Object.entries(tries)) {
    const r = await run();
    console.log(`\n=== ${model} / ${name}: HTTP ${r.status}`);
    const audio = r.status === 200 ? audioOf(r.json) : null;
    console.log(audio ? `heard: ${await heard(audio)}` : r.text.slice(0, 300));
  }
}
