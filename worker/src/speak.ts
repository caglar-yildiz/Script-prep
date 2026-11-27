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
  /** What the character carries underneath the line, e.g. "sleepless, on edge". */
  undertone: string;
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
    // Only plain words reach the voice: like a segment's style.
    undertone: String(input.undertone ?? "").replace(/[^\p{L}\p{N} ,'-]/gu, "").slice(0, 60).trim(),
    segments,
    pronunciations,
  };
}

const VOLUME_WORDS: Record<string, string> = {
  whisper: "whispering",
  quiet: "quietly",
  normal: "at normal volume",
  loud: "loudly",
  shout: "shouting",
};

const PACE_WORDS: Record<string, string> = { slow: "speaking slowly", normal: "", fast: "speaking quickly" };

export interface VoiceInput {
  /** What is said: the script's words only, with English pause tags where the director wants them. */
  text: string;
  /** How it is said, for the turn's speech_metadata style. */
  style: string;
}

/**
 * Splits a noted line into the two things Gemini 3.8 voices take: the transcript, which is spoken
 * word for word, and the style, which is followed but never spoken. A line that changes delivery
 * part way (shouted, then whispered) is described in order in the style, with a pause tag at the
 * change. Angle brackets in the script are removed so a line cannot add tags of its own.
 */
export function voiceInput(request: SpeakRequest): VoiceInput {
  const parts = request.segments.map((segment, i) => {
    let spoken = segment.text.replace(/[<>|]/g, "");
    for (const { word, say } of request.pronunciations) {
      spoken = spoken.split(word).join(say.replace(/[<>|]/g, ""));
    }
    const last = i === request.segments.length - 1;
    const pause = last ? "" : segment.pauseAfterMs >= 600 ? " <long pause>" : segment.pauseAfterMs >= 250 ? " <short pause>" : "";
    return spoken + pause;
  });
  const delivery = (segment: SpeakRequest["segments"][number], many: boolean) =>
    [segment.volume === "normal" && !many ? "" : VOLUME_WORDS[segment.volume], segment.style].filter(Boolean).join(", ");
  const many = request.segments.length > 1
    && new Set(request.segments.map((s) => `${s.volume}|${s.style}`)).size > 1;
  const turn = [request.emotion !== "neutral" ? request.emotion : "",
    request.undertone ? `underneath: ${request.undertone}` : "", PACE_WORDS[request.pace]].filter(Boolean);
  if (many) {
    const steps = request.segments.map((s) => delivery(s, true));
    turn.push(steps.map((step, i) => (i === 0 ? `first ${step}` : `then ${step}`)).join(", "));
  } else {
    turn.push(delivery(request.segments[0], false));
  }
  const style = turn.filter(Boolean).join("; ");
  return { text: parts.join(" "), style: style || "natural, as in a stage rehearsal" };
}

/**
 * The studio voice of each of the apps' ten character voices (speakers 0–4 female, 5–9 male, as
 * the on-device voices). The apps keep the same table; the sample voice pack is made with it.
 */
export const CAST_VOICES = ["Kore", "Aoede", "Leda", "Sulafat", "Gacrux", "Charon", "Puck", "Orus", "Algenib", "Iapetus"] as const;

/**
 * The prompt for the older speech models: the delivery as a direction, then the line. Pause tags
 * become ellipses, which those models pause at; on CI they spoke only the line.
 */
export function legacyPrompt(input: VoiceInput): string {
  const line = input.text.replace(/\s*<long pause>\s*/g, "... ").replace(/\s*<short pause>\s*/g, ", ")
    .replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
  return `Read the line below as an actor in a play, ${input.style}. Say only the line.\n\n${line}`;
}
