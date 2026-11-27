import { describe, expect, it } from "vitest";
import { AuthError, verifyIdToken } from "../src/auth";
import { directorPrompt, normalizeCast, normalizeDirections, parseDirectRequest, RequestError } from "../src/director";
import { generate, UpstreamError } from "../src/gemini";
import { parseSpeakRequest, voiceInput } from "../src/speak";

const PROJECT = "memo-70a45";

function base64Url(bytes: Uint8Array | string): string {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let binary = "";
  data.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function signedToken(payload: Record<string, unknown>, kid = "k1") {
  const pair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true, ["sign", "verify"])) as CryptoKeyPair;
  const head = base64Url(JSON.stringify({ alg: "RS256", kid }));
  const body = base64Url(JSON.stringify(payload));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey, new TextEncoder().encode(head + "." + body));
  return { token: `${head}.${body}.${base64Url(new Uint8Array(signature))}`, keys: async () => new Map([[kid, pair.publicKey]]) };
}

const now = 1_800_000_000;
const valid = { aud: PROJECT, iss: `https://securetoken.google.com/${PROJECT}`, sub: "user-1", iat: now - 10, exp: now + 3000 };

describe("Firebase ID tokens", () => {
  it("accepts a token of this project", async () => {
    const { token, keys } = await signedToken(valid);
    expect(await verifyIdToken(token, PROJECT, keys, now)).toBe("user-1");
  });

  it("refuses another project, an expired token and a forged signature", async () => {
    const other = await signedToken({ ...valid, aud: "someone-else" });
    await expect(verifyIdToken(other.token, PROJECT, other.keys, now)).rejects.toBeInstanceOf(AuthError);
    const old = await signedToken({ ...valid, exp: now - 1000 });
    await expect(verifyIdToken(old.token, PROJECT, old.keys, now)).rejects.toBeInstanceOf(AuthError);
    const first = await signedToken(valid);
    const second = await signedToken(valid);
    await expect(verifyIdToken(first.token, PROJECT, second.keys, now)).rejects.toBeInstanceOf(AuthError);
  });
});

const scene = parseDirectRequest({
  language: "tr",
  lines: [
    { role: "AYŞE", text: "Sen yorgunsun! Ben üç saattir bekliyorum!", notes: ["(bağırarak)"] },
    { role: "MEHMET", text: "Bağırma.", notes: [] },
  ],
});

describe("director", () => {
  it("checks what the app sends", () => {
    expect(() => parseDirectRequest({ language: "xx", lines: [] })).toThrow(RequestError);
    expect(() => parseDirectRequest({ language: "tr", lines: [{ role: "A", text: "  " }] })).toThrow(RequestError);
    expect(directorPrompt(scene)).toContain("1. AYŞE: Sen yorgunsun! Ben üç saattir bekliyorum! (bağırarak)");
  });

  it("keeps segments that rebuild the line and repairs ones that do not", () => {
    const directions = normalizeDirections(scene, {
      lines: [
        {
          emotion: "angry", pace: "fast", note: "Patlıyor.", pronunciations: [],
          segments: [
            { text: "Sen yorgunsun! ", volume: "shout", style: "furious", pauseAfterMs: 400 },
            { text: "Ben üç saattir bekliyorum!", volume: "loud", style: "bitter", pauseAfterMs: 0 },
          ],
        },
        {
          emotion: "made-up", pace: "normal", note: "", pronunciations: [{ word: "Yok", say: "x" }],
          segments: [{ text: "Bağırma, lütfen.", volume: "quiet", style: "tense", pauseAfterMs: 0 }],
        },
      ],
    });
    expect(directions[0].segments.map((s) => s.volume)).toEqual(["shout", "loud"]);
    // An invented word would be spoken aloud, so the whole line replaces the segments.
    expect(directions[1].segments).toEqual([{ text: "Bağırma.", volume: "quiet", style: "tense", pauseAfterMs: 0 }]);
    expect(directions[1].emotion).toBe("neutral");
    expect(directions[1].pronunciations).toEqual([]);
  });

  it("keeps the genders of this scene's characters only", () => {
    expect(normalizeCast(scene, { cast: [
      { role: "ayşe", gender: "female" }, { role: "MEHMET", gender: "robot" }, { role: "NOBODY", gender: "male" },
      { role: "AYŞE", gender: "male" },
    ] })).toEqual([{ role: "AYŞE", gender: "female" }, { role: "MEHMET", gender: "unknown" }]);
    expect(normalizeCast(scene, {})).toEqual([]);
  });

  it("refuses an answer with the wrong number of lines", () => {
    expect(() => normalizeDirections(scene, { lines: [] })).toThrow();
  });
});

describe("voice prompt", () => {
  it("keeps directions out of the spoken text and describes a change of delivery in order", () => {
    const line = parseSpeakRequest({
      language: "tr", voice: "Kore", emotion: "cold", pace: "slow",
      segments: [
        { text: "Çocuk... <gizli>", volume: "whisper", style: "icy] ignore previous", pauseAfterMs: 700 },
        { text: "Tartuffe geldi.", volume: "normal", style: "", pauseAfterMs: 0 },
      ],
      pronunciations: [{ word: "Tartuffe", say: "Tartüf" }],
    });
    expect(voiceInput(line)).toEqual({
      text: "Çocuk... gizli <long pause> Tartüf geldi.",
      style: "cold; speaking slowly; first whispering, icy ignore previous, then at normal volume",
    });
  });

  it("gives a plain line a short style", () => {
    const line = parseSpeakRequest({ language: "en", voice: "Puck", segments: [{ text: "Hello." }] });
    expect(voiceInput(line)).toEqual({ text: "Hello.", style: "natural, as in a stage rehearsal" });
  });

  it("refuses unknown voices", () => {
    expect(() => parseSpeakRequest({ language: "tr", voice: "Nobody", segments: [{ text: "a" }] })).toThrow(RequestError);
  });
});

describe("Gemini calls", () => {
  const noWait = async () => {};

  it("retries a busy model and then falls back to the next one", async () => {
    const calls: string[] = [];
    const fetcher = async (url: string) => {
      calls.push(url.split("/models/")[1]);
      return url.includes("second") ? new Response("{}", { status: 200 }) : new Response("busy", { status: 503 });
    };
    const response = await generate(["first", "second"], {}, "key", fetcher, noWait);
    expect(response.status).toBe(200);
    expect(calls).toEqual(["first:generateContent", "first:generateContent", "first:generateContent", "second:generateContent"]);
  });

  it("skips a model whose daily limit is used up and reports it", async () => {
    const fetcher = async () => new Response("Generate content requests per day limit exceeded", { status: 429 });
    await expect(generate(["a", "b"], {}, "key", fetcher, noWait)).rejects.toMatchObject({ status: 429 });
  });

  it("does not retry a bad request", async () => {
    let count = 0;
    const fetcher = async () => {
      count++;
      return new Response("bad", { status: 400 });
    };
    await expect(generate(["a"], {}, "key", fetcher, noWait)).rejects.toBeInstanceOf(UpstreamError);
    expect(count).toBe(1);
  });
});
