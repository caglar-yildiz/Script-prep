/**
 * The "director": reads a scene and returns how each line should be performed, in a form the
 * voice step (and the on-device voice) can apply: emotion, pace, and parts of the line with their
 * own loudness and style.
 */

export const LANGUAGES = ["tr", "en", "es", "de", "fr", "pt", "it"] as const;
export type Language = (typeof LANGUAGES)[number];

export const EMOTIONS = ["neutral", "angry", "sarcastic", "sad", "tired", "afraid", "tender", "excited",
  "desperate", "cold", "pleading", "hurt", "happy", "nervous"] as const;
export const PACES = ["slow", "normal", "fast"] as const;
export const VOLUMES = ["whisper", "quiet", "normal", "loud", "shout"] as const;
export const GENDERS = ["female", "male", "unknown"] as const;

/** A character's likely gender, so the apps can give them a matching voice. */
export interface CastMember {
  role: string;
  gender: (typeof GENDERS)[number];
}

export const MAX_LINES = 80;
const MAX_LINE_CHARS = 1500;
const MAX_SCENE_CHARS = 20_000;
const MAX_NOTE_CHARS = 300;

const LANGUAGE_NAMES: Record<Language, string> = {
  tr: "Turkish", en: "English", es: "Spanish", de: "German", fr: "French", pt: "Portuguese", it: "Italian",
};

export interface SceneLine {
  role: string;
  text: string;
  notes: string[];
}

export interface DirectRequest {
  language: Language;
  title: string;
  lines: SceneLine[];
}

export interface Segment {
  text: string;
  volume: (typeof VOLUMES)[number];
  style: string;
  pauseAfterMs: number;
}

export interface Direction {
  emotion: (typeof EMOTIONS)[number];
  pace: (typeof PACES)[number];
  note: string;
  segments: Segment[];
  pronunciations: { word: string; say: string }[];
}

export class RequestError extends Error {}

/**
 * The script a request belongs to: an id the app makes from its own script id, so the monthly
 * allowance can count scripts. Apps that send none share one bucket.
 */
export function scriptOf(body: unknown): string {
  const value = (body as { script?: unknown } | null)?.script;
  if (value === undefined) {
    return "unnamed";
  }
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(value)) {
    throw new RequestError("script must be a short id");
  }
  return value;
}

function text(value: unknown, max: number, field: string): string {
  if (typeof value !== "string") {
    throw new RequestError(`${field} must be text`);
  }
  if (value.length > max) {
    throw new RequestError(`${field} is too long`);
  }
  return value;
}

/** Checks and trims what an app sent; throws RequestError when it is not a scene we accept. */
export function parseDirectRequest(body: unknown): DirectRequest {
  if (typeof body !== "object" || body === null) {
    throw new RequestError("Expected a JSON object");
  }
  const input = body as Record<string, unknown>;
  const language = input.language as Language;
  if (!LANGUAGES.includes(language)) {
    throw new RequestError("Unsupported language");
  }
  if (!Array.isArray(input.lines) || input.lines.length === 0 || input.lines.length > MAX_LINES) {
    throw new RequestError(`Send between 1 and ${MAX_LINES} lines`);
  }
  let total = 0;
  const lines = input.lines.map((raw, i) => {
    const line = raw as Record<string, unknown>;
    const notes = Array.isArray(line.notes) ? line.notes.slice(0, 10).map((n) => text(n, MAX_NOTE_CHARS, "note")) : [];
    const entry = {
      role: text(line.role, 60, `lines[${i}].role`).trim(),
      text: text(line.text, MAX_LINE_CHARS, `lines[${i}].text`).trim(),
      notes,
    };
    if (!entry.text) {
      throw new RequestError(`lines[${i}] has no text`);
    }
    total += entry.text.length + notes.join(" ").length;
    return entry;
  });
  if (total > MAX_SCENE_CHARS) {
    throw new RequestError("Scene is too long");
  }
  return { language, title: typeof input.title === "string" ? input.title.slice(0, 200) : "", lines };
}

export function directorPrompt(request: DirectRequest): string {
  const scene = request.lines.map((line, i) => {
    const directions = line.notes.map((n) => (/^[([]/.test(n) ? ` ${n}` : ` (${n})`)).join("");
    return `${i + 1}. ${line.role}: ${line.text}${directions}`;
  }).join("\n");
  return `You are a theatre director preparing a voice actor to read the other characters' lines of a ${LANGUAGE_NAMES[request.language]} play.
For every numbered line, decide how it should be performed, using the whole scene, the relationships and the stage directions in parentheses (stage directions after a line describe how it, or the next moment, is played).
Rules:
- Return exactly one entry per numbered line, in the same order.
- segments: split a line only where the delivery changes (for example shouted, then whispered). The segment texts joined with spaces must reproduce the spoken line exactly, without the stage directions.
- volume: whisper, quiet, normal, loud or shout. style: 2-6 English words for the voice actor, e.g. "icy, mocking" or "breaking, close to tears".
- note: one short sentence in ${LANGUAGE_NAMES[request.language]} explaining the subtext to the actor.
- cast: every character who speaks, with their likely gender from the name, the text and the stage directions (female, male, or unknown when it cannot be told).
- pronunciations: only words a speech engine is likely to misread (foreign names, abbreviations, numbers), each with how to say it in ${LANGUAGE_NAMES[request.language]} spelling. Usually empty.
${request.title ? `Play: ${request.title}\n` : ""}
SCENE:
${scene}`;
}

export const DIRECTION_SCHEMA = {
  type: "object",
  properties: {
    cast: {
      type: "array",
      items: {
        type: "object",
        properties: { role: { type: "string" }, gender: { type: "string", enum: [...GENDERS] } },
        required: ["role", "gender"],
      },
    },
    lines: {
      type: "array",
      items: {
        type: "object",
        properties: {
          emotion: { type: "string", enum: [...EMOTIONS] },
          pace: { type: "string", enum: [...PACES] },
          note: { type: "string" },
          segments: {
            type: "array",
            items: {
              type: "object",
              properties: {
                text: { type: "string" },
                volume: { type: "string", enum: [...VOLUMES] },
                style: { type: "string" },
                pauseAfterMs: { type: "integer" },
              },
              required: ["text", "volume", "style", "pauseAfterMs"],
            },
          },
          pronunciations: {
            type: "array",
            items: {
              type: "object",
              properties: { word: { type: "string" }, say: { type: "string" } },
              required: ["word", "say"],
            },
          },
        },
        required: ["emotion", "pace", "note", "segments", "pronunciations"],
      },
    },
  },
  required: ["cast", "lines"],
};

/** The genders the director gave, for characters of this scene only. */
export function normalizeCast(request: DirectRequest, answer: unknown): CastMember[] {
  const roles = new Map(request.lines.map((line) => [line.role.toLowerCase(), line.role]));
  const cast = (answer as { cast?: unknown[] } | null)?.cast;
  const seen = new Set<string>();
  return (Array.isArray(cast) ? cast : []).flatMap((raw) => {
    const entry = (raw ?? {}) as Record<string, unknown>;
    const role = roles.get(String(entry.role ?? "").trim().toLowerCase());
    if (!role || seen.has(role)) {
      return [];
    }
    seen.add(role);
    return [{ role, gender: pick(GENDERS, entry.gender, "unknown") }];
  });
}

function squash(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

function pick<T extends readonly string[]>(values: T, value: unknown, fallback: T[number]): T[number] {
  return values.includes(value as string) ? (value as T[number]) : fallback;
}

/**
 * Makes the model's answer safe to use: one direction per line, and segments that rebuild each
 * line exactly. A line whose segments do not match its text gets one segment with the whole line,
 * so the voice never says words that are not in the script.
 */
export function normalizeDirections(request: DirectRequest, answer: unknown): Direction[] {
  const lines = (answer as { lines?: unknown[] } | null)?.lines;
  if (!Array.isArray(lines) || lines.length !== request.lines.length) {
    throw new Error("The director returned a different number of lines");
  }
  return lines.map((raw, i) => {
    const entry = (raw ?? {}) as Record<string, unknown>;
    const spoken = squash(request.lines[i].text);
    const rawSegments = Array.isArray(entry.segments) ? entry.segments : [];
    let segments: Segment[] = rawSegments.map((s) => {
      const segment = (s ?? {}) as Record<string, unknown>;
      return {
        text: squash(String(segment.text ?? "")),
        volume: pick(VOLUMES, segment.volume, "normal"),
        style: squash(String(segment.style ?? "")).slice(0, 60),
        pauseAfterMs: Math.max(0, Math.min(3000, Math.round(Number(segment.pauseAfterMs) || 0))),
      };
    }).filter((s) => s.text.length > 0);
    // Spacing between segments does not matter, only the words and punctuation.
    const bare = (value: string) => value.replace(/\s+/g, "");
    if (bare(segments.map((s) => s.text).join("")) !== bare(spoken)) {
      const first = segments[0];
      segments = [{ text: spoken, volume: first?.volume ?? "normal", style: first?.style ?? "", pauseAfterMs: 0 }];
    }
    const pronunciations = (Array.isArray(entry.pronunciations) ? entry.pronunciations : [])
      .map((p) => p as Record<string, unknown>)
      .filter((p) => typeof p.word === "string" && typeof p.say === "string" && p.word && p.say
        && spoken.includes(p.word as string))
      .slice(0, 10)
      .map((p) => ({ word: (p.word as string).slice(0, 40), say: (p.say as string).slice(0, 60) }));
    return {
      emotion: pick(EMOTIONS, entry.emotion, "neutral"),
      pace: pick(PACES, entry.pace, "normal"),
      note: squash(String(entry.note ?? "")).slice(0, 240),
      segments,
      pronunciations,
    };
  });
}
