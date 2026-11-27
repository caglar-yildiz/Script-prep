/**
 * Makes the studio voice pack of the sample scene the apps open first, so everyone can hear studio
 * voices before preparing a script of their own. For every language: the director's notes and the
 * characters' genders, then each line's audio, saved as AAC beside a JSON index:
 *
 *   app/src/main/assets/studio-sample/<lang>.json, <lang>-<n>.m4a
 *   ios/MemoEverything/Resources/StudioSample/studio-sample-<lang>.json, studio-sample-<lang>-<n>.m4a
 *
 * Lines already in a pack are kept, so a run stopped by a limit continues where it stopped.
 * Run with GEMINI_API_KEY set: npx tsx worker/scripts/sample-pack.ts
 */
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { DIRECTION_SCHEMA, DirectRequest, directorPrompt, Language, normalizeCast, normalizeDirections } from "../src/director";
import { answerText, DIRECTOR_MODELS, generate, interact, UpstreamError, VOICE_MODELS } from "../src/gemini";
import { CAST_VOICES, parseSpeakRequest, voiceInput } from "../src/speak";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const ANDROID = join(ROOT, "app/src/main/assets/studio-sample");
const IOS = join(ROOT, "ios/MemoEverything/Resources/StudioSample");
const FOLDERS: Record<Language, string> = { en: "values", tr: "values-tr", de: "values-de", es: "values-es",
  fr: "values-fr", it: "values-it", pt: "values-pt" };
const key = process.env.GEMINI_API_KEY ?? "";
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

interface PackLine {
  role: string;
  text: string;
  voice: string;
  note: unknown;
  audio: string;
}

interface Pack {
  version: 1;
  language: Language;
  cast: Record<string, string>;
  lines: PackLine[];
}

function sampleScript(language: Language): string {
  const xml = readFileSync(join(ROOT, "app/src/main/res", FOLDERS[language], "strings.xml"), "utf8");
  const raw = /<string name="sample_script">([\s\S]*?)<\/string>/.exec(xml)?.[1];
  if (!raw) {
    throw new Error(`No sample in ${language}`);
  }
  return raw.replace(/\\n/g, "\n").replace(/\\'/g, "'").replace(/\\"/g, '"')
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
}

/** The sample's spoken lines; a stage direction is a note of the line before it. */
function parse(script: string): DirectRequest["lines"] {
  const lines: DirectRequest["lines"] = [];
  for (const row of script.split("\n").map((r) => r.trim()).filter(Boolean)) {
    const spoken = /^([^:()]+?)\s*:\s*(.+)$/.exec(row);
    if (spoken) {
      lines.push({ role: spoken[1].trim(), text: spoken[2].trim(), notes: [] });
    } else if (lines.length) {
      lines[lines.length - 1].notes.push(row);
    }
  }
  return lines;
}

/** Retries per-minute limits until they pass; a daily limit ends the run. */
async function patiently<T>(work: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await work();
    } catch (error) {
      if (error instanceof UpstreamError && error.status === 503 && attempt < 12) {
        console.log("  busy, waiting 30 s");
        await wait(30_000);
        continue;
      }
      throw error;
    }
  }
}

function audioOf(interaction: { steps?: { content?: { type?: string; data?: string }[] }[] }): Buffer {
  for (const step of interaction.steps ?? []) {
    for (const part of step.content ?? []) {
      if (part.type === "audio" && part.data) {
        return Buffer.from(part.data, "base64");
      }
    }
  }
  throw new Error("No audio in the answer");
}

async function makePack(language: Language): Promise<void> {
  mkdirSync(ANDROID, { recursive: true });
  mkdirSync(IOS, { recursive: true });
  const index = join(ANDROID, `${language}.json`);
  const lines = parse(sampleScript(language));
  let pack: Pack | null = existsSync(index) ? JSON.parse(readFileSync(index, "utf8")) : null;
  const sameScene = pack && pack.lines.length === lines.length
    && pack.lines.every((line, i) => line.role === lines[i].role && line.text === lines[i].text);
  if (!pack || !sameScene) {
    console.log(`${language}: directing ${lines.length} lines`);
    const request: DirectRequest = { language, title: "", lines };
    const answer = JSON.parse(await answerText(await patiently(() => generate(DIRECTOR_MODELS, {
      contents: [{ parts: [{ text: directorPrompt(request) }] }],
      generationConfig: { responseMimeType: "application/json", responseSchema: DIRECTION_SCHEMA, temperature: 0.7 },
    }, key))));
    const notes = normalizeDirections(request, answer);
    const cast: Record<string, string> = {};
    for (const member of normalizeCast(request, answer)) {
      cast[member.role] = member.gender;
    }
    // The apps' automatic cast: women take speakers 0, 1, 2…, men 5, 6, 7…
    const next = { female: 0, male: 5 };
    const voices = new Map<string, string>();
    for (const role of [...new Set(lines.map((l) => l.role))]) {
      const gender = cast[role] === "male" ? "male" : cast[role] === "female" ? "female" : next.female <= next.male - 5 ? "female" : "male";
      voices.set(role, CAST_VOICES[next[gender]++]);
    }
    pack = {
      version: 1, language, cast,
      lines: lines.map((line, i) => ({ role: line.role, text: line.text, voice: voices.get(line.role)!, note: notes[i], audio: "" })),
    };
  }
  for (const [i, line] of pack.lines.entries()) {
    const name = `${language}-${i + 1}.m4a`;
    if (line.audio && existsSync(join(ANDROID, line.audio))) {
      continue;
    }
    const note = line.note as Record<string, unknown>;
    const { text, style } = voiceInput(parseSpeakRequest({ language, voice: line.voice, ...note }));
    console.log(`${language} ${i + 1}: ${line.voice} | ${style} | ${text}`);
    const interaction = await (await patiently(() => interact(VOICE_MODELS, (model) => ({
      model,
      input: [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style }] }] }],
      response_format: { type: "audio" },
      generation_config: { speech_config: [{ voice: line.voice }] },
    }), key))).json();
    const wav = join(ANDROID, `${language}-${i + 1}.wav`);
    writeFileSync(wav, audioOf(interaction as never));
    execFileSync("ffmpeg", ["-loglevel", "error", "-y", "-i", wav, "-ac", "1", "-c:a", "aac", "-b:a", "48k", join(ANDROID, name)]);
    rmSync(wav);
    line.audio = name;
    writeFileSync(index, JSON.stringify(pack, null, 1) + "\n");
  }
  writeFileSync(index, JSON.stringify(pack, null, 1) + "\n");
  // iOS keeps its resources in one flat bundle, so its names carry a prefix.
  const ios: Pack = { ...pack, lines: pack.lines.map((line) => ({ ...line, audio: line.audio && `studio-sample-${line.audio}` })) };
  writeFileSync(join(IOS, `studio-sample-${language}.json`), JSON.stringify(ios, null, 1) + "\n");
  for (const line of pack.lines.filter((l) => l.audio)) {
    copyFileSync(join(ANDROID, line.audio), join(IOS, `studio-sample-${line.audio}`));
  }
}

const languages = (process.argv[2]?.split(",") ?? Object.keys(FOLDERS)) as Language[];
let stopped = false;
for (const language of languages) {
  try {
    await makePack(language);
  } catch (error) {
    console.log(`${language}: stopped: ${error instanceof Error ? error.message : error}`);
    stopped = true;
    break;
  }
}
process.exitCode = stopped ? 2 : 0;
