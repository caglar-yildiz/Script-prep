import { EMOTIONS, LANGUAGES, Language, PACES, RequestError, VOLUMES } from "./director";

/** Gemini's built-in voices that a character can be given. */
export const VOICES = ["Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe",
  "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
  "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
  "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"] as const;

const MAX_SPOKEN_CHARS = 1500;

export interface SpeakRequest {
  language: Language;
  voice: (typeof VOICES)[number];
  emotion: (typeof EMOTIONS)[number];
  pace: (typeof PACES)[number];
  segments: { text: string; volume: (typeof VOLUMES)[number]; style: string; pauseAfterMs: number }[];
  pronunciations: { word: string; say: string }[];
}

export function parseSpeakRequest(body: unknown): SpeakRequest {
  if (typeof body !== "object" || body === null) {
    throw new RequestError("Expected a JSON object");
  }
  const input = body as Record<string, unknown>;
  if (!LANGUAGES.includes(input.language as Language)) {
    throw new RequestError("Unsupported language");
  }
  if (!VOICES.includes(input.voice as (typeof VOICES)[number])) {
    throw new RequestError("Unknown voice");
  }
  if (!Array.isArray(input.segments) || input.segments.length === 0 || input.segments.length > 12) {
    throw new RequestError("Send between 1 and 12 segments");
  }
  let total = 0;
  const segments = input.segments.map((raw) => {
    const s = (raw ?? {}) as Record<string, unknown>;
    if (typeof s.text !== "string" || !s.text.trim()) {
      throw new RequestError("Every segment needs text");
    }
    total += s.text.length;
    return {
      text: s.text.replace(/\s+/g, " ").trim(),
      volume: VOLUMES.includes(s.volume as never) ? (s.volume as SpeakRequest["segments"][number]["volume"]) : "normal",
      // Only plain words reach the voice prompt: no brackets or line breaks a user could abuse.
      style: String(s.style ?? "").replace(/[^\p{L}\p{N} ,'-]/gu, "").slice(0, 60).trim(),
      pauseAfterMs: Math.max(0, Math.min(3000, Math.round(Number(s.pauseAfterMs) || 0))),
    };
  });
  if (total > MAX_SPOKEN_CHARS) {
    throw new RequestError("Line is too long");
  }
  const pronunciations = (Array.isArray(input.pronunciations) ? input.pronunciations : []).slice(0, 10)
    .map((p) => p as Record<string, unknown>)
    .filter((p) => typeof p.word === "string" && typeof p.say === "string")
    .map((p) => ({ word: (p.word as string).slice(0, 40), say: (p.say as string).replace(/[[\]]/g, "").slice(0, 60) }));
  return {
    language: input.language as Language,
    voice: input.voice as SpeakRequest["voice"],
    emotion: EMOTIONS.includes(input.emotion as never) ? (input.emotion as SpeakRequest["emotion"]) : "neutral",
    pace: PACES.includes(input.pace as never) ? (input.pace as SpeakRequest["pace"]) : "normal",
    segments,
    pronunciations,
  };
}

const VOLUME_TAGS: Record<string, string> = {
  whisper: "whispering",
  quiet: "quietly",
  normal: "",
  loud: "loudly",
  shout: "shouting",
};

/**
 * The text sent to the voice model: performance directions in square brackets, which the model
 * follows without reading them aloud, and the line itself. Brackets in the script are removed so
 * they cannot be taken for directions.
 */
export function voicePrompt(request: SpeakRequest): string {
  const opening = [request.emotion !== "neutral" ? request.emotion : "", request.pace !== "normal" ? `${request.pace} pace` : ""]
    .filter(Boolean).join(", ");
  const parts = request.segments.map((segment) => {
    let spoken = segment.text.replace(/[[\]]/g, "");
    for (const { word, say } of request.pronunciations) {
      spoken = spoken.split(word).join(say);
    }
    const tag = [VOLUME_TAGS[segment.volume], segment.style].filter(Boolean).join(", ");
    const pause = segment.pauseAfterMs >= 600 ? " [long pause]" : segment.pauseAfterMs >= 250 ? " [short pause]" : "";
    return (tag ? `[${tag}] ` : "") + spoken + pause;
  });
  return (opening ? `[${opening}] ` : "") + parts.join(" ");
}
