/**
 * The director's first reading of a whole play: the story, the characters, how they stand to one
 * another and, scene by scene, what happens and how each character feels. The apps keep it and
 * send the relevant part with every request for line notes, so a line deep in a long play is
 * still played knowing what came before.
 *
 * The app sends the play as plain text (the cheapest form for the Worker to pass on):
 *   ## SCENE: <name>       a scene starts (the names come back in the answer)
 *   ROLE: text (direction)
 */
import { GENDERS, LANGUAGE_NAMES, Language, LANGUAGES, RequestError } from "./director";

/** About a long play's length; the model reads far more, the Worker keeps its own work small. */
export const MAX_PLAY_CHARS = 600_000;

export interface AnalyzeRequest {
  language: Language;
  title: string;
  play: string;
}

export function parseAnalyzeRequest(language: string | null, title: string | null, play: string): AnalyzeRequest {
  if (!LANGUAGES.includes(language as Language)) {
    throw new RequestError("Unsupported language");
  }
  if (!play.trim()) {
    throw new RequestError("The play is empty");
  }
  if (play.length > MAX_PLAY_CHARS) {
    throw new RequestError("The play is too long");
  }
  return { language: language as Language, title: (title ?? "").slice(0, 200), play };
}

export function analyzePrompt(request: AnalyzeRequest): string {
  const name = LANGUAGE_NAMES[request.language];
  return `You are a theatre director reading a whole ${name} play before rehearsals, to guide a voice actor who will read the other characters' lines.
Read the play below and write down, in ${name}:
- summary: what the play is about and how it develops, in 3-6 sentences.
- characters: every character who speaks, with gender (female, male or unknown), age (child, young, adult, old or unknown) and a short description of who they are and their usual manner.
- relationships: the important ties between characters (love, rivalry, fear, debt, secrets...), each in one sentence.
- scenes: for every "## SCENE:" heading, in order:
  - scene: the exact heading text;
  - summary: what happens in it (2-4 sentences);
  - atmosphere: what everyone in it shares and carries into every line, whether or not anyone says so: the hour, tiredness, fear, grief, suspicion, what has just happened (1-2 sentences);
  - moods: the state of mind of each character who speaks in it under that atmosphere, especially pressure, fear, exhaustion, anger, guilt or lies the lines themselves do not state. A character who stays composed still feels the atmosphere: say what it costs them.
  If the play has no headings, return one scene with an empty scene name.
${request.title ? `Play: ${request.title}\n` : ""}
PLAY:
${request.play}`;
}

const text = { type: "string" };

export const ANALYSIS_SCHEMA = {
  type: "object",
  properties: {
    summary: text,
    characters: {
      type: "array",
      items: {
        type: "object",
        properties: {
          role: text,
          gender: { type: "string", enum: [...GENDERS] },
          age: { type: "string", enum: ["child", "young", "adult", "old", "unknown"] },
          description: text,
        },
        required: ["role", "gender", "age", "description"],
      },
    },
    relationships: { type: "array", items: text },
    scenes: {
      type: "array",
      items: {
        type: "object",
        properties: {
          scene: text,
          summary: text,
          atmosphere: text,
          moods: {
            type: "array",
            items: { type: "object", properties: { role: text, mood: text }, required: ["role", "mood"] },
          },
        },
        required: ["scene", "summary", "atmosphere", "moods"],
      },
    },
  },
  required: ["summary", "characters", "relationships", "scenes"],
};

export interface Analysis {
  summary: string;
  characters: { role: string; gender: string; age: string; description: string }[];
  relationships: string[];
  scenes: { scene: string; summary: string; atmosphere: string; moods: { role: string; mood: string }[] }[];
}

function clean(value: unknown, max: number): string {
  return String(value ?? "").replace(/\s+/g, " ").trim().slice(0, max);
}

function list(value: unknown, max: number): Record<string, unknown>[] {
  return (Array.isArray(value) ? value : []).slice(0, max).map((v) => (v ?? {}) as Record<string, unknown>);
}

/** Keeps the answer's shape and sizes predictable for the apps. */
export function normalizeAnalysis(answer: unknown): Analysis {
  const input = (answer ?? {}) as Record<string, unknown>;
  return {
    summary: clean(input.summary, 1500),
    characters: list(input.characters, 60).map((c) => ({
      role: clean(c.role, 60),
      gender: GENDERS.includes(c.gender as never) ? (c.gender as string) : "unknown",
      age: ["child", "young", "adult", "old"].includes(c.age as string) ? (c.age as string) : "unknown",
      description: clean(c.description, 400),
    })).filter((c) => c.role),
    relationships: (Array.isArray(input.relationships) ? input.relationships : []).slice(0, 40)
      .map((r) => clean(r, 300)).filter(Boolean),
    scenes: list(input.scenes, 300).map((s) => ({
      scene: clean(s.scene, 200),
      summary: clean(s.summary, 800),
      atmosphere: clean(s.atmosphere, 500),
      moods: list(s.moods, 30).map((m) => ({ role: clean(m.role, 60), mood: clean(m.mood, 300) }))
        .filter((m) => m.role && m.mood),
    })),
  };
}
