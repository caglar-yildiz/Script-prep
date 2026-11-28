/**
 * "Fix this line": the actor hears a studio line and asks for it differently (angrier, whispered,
 * a word said another way). The director rewrites that one line's direction to do what was
 * asked, keeping what still fits, in the same form as the scene's notes.
 */
import { DirectRequest, Direction, directorPrompt, normalizeDirections, parseDirectRequest, RequestError } from "./director";

/** One-tap wishes the apps offer, in the words the director reads. */
export const WISHES: Record<string, string> = {
  angrier: "angrier",
  calmer: "calmer and more controlled",
  whisper: "whispered",
  shout: "shouted",
  tired: "more tired and worn out",
  scared: "more frightened",
  sadder: "sadder",
  slower: "slower",
  faster: "faster",
};

export interface ReviseRequest {
  scene: DirectRequest;
  /** The line's direction as it is now, as JSON. */
  current: string;
  wishes: string[];
  /** What the actor typed, in their own words and language. */
  request: string;
  pronunciations: { word: string; say: string }[];
}

function plain(value: unknown, max: number): string {
  return String(value ?? "").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

export function parseReviseRequest(body: unknown): ReviseRequest {
  const scene = parseDirectRequest(body);
  if (scene.lines.length !== 1) {
    throw new RequestError("Send the one line to change");
  }
  const input = body as Record<string, unknown>;
  const wishes = (Array.isArray(input.wishes) ? input.wishes : [])
    .filter((w): w is string => typeof w === "string" && w in WISHES).slice(0, 5);
  const request = plain(input.request, 300);
  const pronunciations = (Array.isArray(input.pronunciations) ? input.pronunciations : []).slice(0, 5)
    .map((p) => (p ?? {}) as Record<string, unknown>)
    .map((p) => ({ word: plain(p.word, 40), say: plain(p.say, 60).replace(/[<>[\]|]/g, "") }))
    .filter((p) => p.word && p.say && scene.lines[0].text.includes(p.word));
  if (!wishes.length && !request && !pronunciations.length) {
    throw new RequestError("Say how the line should change");
  }
  const current = typeof input.current === "object" && input.current !== null
    ? JSON.stringify(input.current).slice(0, 2000) : "";
  return { scene, current, wishes, request, pronunciations };
}

export function revisePrompt(revise: ReviseRequest): string {
  const asked = [
    ...revise.wishes.map((w) => WISHES[w]),
    revise.request ? `in the actor's own words: "${revise.request}"` : "",
    ...revise.pronunciations.map((p) => `say "${p.word}" as "${p.say}"`),
  ].filter(Boolean).join("; ");
  return `${directorPrompt(revise.scene)}

THE ACTOR'S NOTE ON LINE 1
${revise.current ? `The line is directed like this now: ${revise.current}\n` : ""}The actor heard it and wants it played differently: ${asked}.
Return one entry for line 1 that does what the actor asks, in the same form as before (style words in English), keeping what still fits the scene. A wish about how a word is said goes into pronunciations.`;
}

/** The new direction, with the actor's own pronunciations kept whatever the model did with them. */
export function revisedDirection(revise: ReviseRequest, answer: unknown): Direction {
  const [direction] = normalizeDirections(revise.scene, answer);
  const words = new Set(revise.pronunciations.map((p) => p.word));
  direction.pronunciations = [...revise.pronunciations,
    ...direction.pronunciations.filter((p) => !words.has(p.word))].slice(0, 10);
  return direction;
}
