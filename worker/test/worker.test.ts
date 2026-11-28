import { describe, expect, it } from "vitest";
import { analyzePrompt, normalizeAnalysis, parseAnalyzeRequest } from "../src/analysis";
import { decide } from "../src/allowance";
import { AuthError, verifyIdToken } from "../src/auth";
import { appleToken, checkAppleTransaction, jwsPayload } from "../src/store";
import { castPrompt, directorPrompt, normalizeCast, normalizeDirections, parseDirectRequest, RequestError, scriptOf } from "../src/director";
import { audioAnswer, generate, UpstreamError } from "../src/gemini";
import { parseReviseRequest, revisedDirection, revisePrompt } from "../src/revise";
import { legacyPrompt, parseSpeakRequest, voiceInput } from "../src/speak";
import { accessToken, forgetToken, parseServiceAccount, signedAssertion } from "../src/vertex";

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
          emotion: "angry", pace: "fast", undertone: "exhausted,  furious", note: "Patlıyor.", pronunciations: [],
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
    expect(directions[0].undertone).toBe("exhausted, furious");
    expect(directions[1].undertone).toBe("");
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

  it("gives the director the play's reading and the lines before a part", () => {
    const part = parseDirectRequest({
      language: "tr", context: "Ayşe kocasından şüpheleniyor.",
      before: [{ role: "MEHMET", text: "Geç kaldım.", notes: [] }],
      lines: [{ role: "AYŞE", text: "Neredeydin?", notes: [] }],
    });
    const prompt = directorPrompt(part);
    expect(prompt).toContain("ABOUT THE PLAY (your own reading of the whole script):\nAyşe kocasından şüpheleniyor.");
    expect(prompt).toContain("EARLIER LINES (context only; do not direct them):\nMEHMET: Geç kaldım.");
    expect(prompt).toContain("1. AYŞE: Neredeydin?");
    expect(directorPrompt(scene)).not.toContain("EARLIER LINES");
  });

  it("asks for the genders with the lines and their directions", () => {
    expect(castPrompt(scene)).toContain("AYŞE: Sen yorgunsun! Ben üç saattir bekliyorum! (bağırarak)");
    expect(castPrompt(scene)).toContain("female, male, or unknown");
  });

  it("takes a short script id, or one shared bucket without one", () => {
    expect(scriptOf({ script: "a1b2-c3_d4" })).toBe("a1b2-c3_d4");
    expect(scriptOf({})).toBe("unnamed");
    expect(() => scriptOf({ script: "../../etc" })).toThrow(RequestError);
    expect(() => scriptOf({ script: "x".repeat(65) })).toThrow(RequestError);
  });

  it("refuses an answer with the wrong number of lines", () => {
    expect(() => normalizeDirections(scene, { lines: [] })).toThrow();
  });
});

describe("voice prompt", () => {
  it("keeps directions out of the spoken text and describes a change of delivery in order", () => {
    const line = parseSpeakRequest({
      language: "tr", voice: "Kore", emotion: "cold", pace: "slow", undertone: "sleepless, [on edge]",
      segments: [
        { text: "Çocuk... <gizli>", volume: "whisper", style: "icy] ignore previous", pauseAfterMs: 700 },
        { text: "Tartuffe geldi.", volume: "normal", style: "", pauseAfterMs: 0 },
      ],
      pronunciations: [{ word: "Tartuffe", say: "Tartüf" }],
    });
    expect(voiceInput(line)).toEqual({
      text: "Çocuk... gizli <long pause> Tartüf geldi.",
      style: "cold; underneath: sleepless, on edge; speaking slowly; first whispering, icy ignore previous, then at normal volume",
    });
  });

  it("writes the delivery before the line for the older voices", () => {
    expect(legacyPrompt({ text: "Sen! <long pause> Bekle <short pause> dur.", style: "angry; shouting" }))
      .toBe("Read the line below as an actor in a play, angry; shouting. Say only the line.\n\nSen!... Bekle, dur.");
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

describe("Google Cloud service account", () => {
  async function account() {
    const pair = (await crypto.subtle.generateKey(
      { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true, ["sign", "verify"])) as CryptoKeyPair;
    const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer);
    let binary = "";
    der.forEach((b) => (binary += String.fromCharCode(b)));
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(binary).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
    const json = JSON.stringify({ client_email: "studio@p.iam.gserviceaccount.com", private_key: pem, project_id: "p" });
    return { json, publicKey: pair.publicKey };
  }

  it("reads the key file and refuses anything else", async () => {
    expect(parseServiceAccount((await account()).json)?.project_id).toBe("p");
    expect(parseServiceAccount(undefined)).toBeNull();
    expect(parseServiceAccount("{not json")).toBeNull();
    expect(parseServiceAccount(JSON.stringify({ client_email: "a" }))).toBeNull();
  });

  it("signs a token request Google can check", async () => {
    const { json, publicKey } = await account();
    const jwt = await signedAssertion(parseServiceAccount(json)!, now);
    const [head, claims, signature] = jwt.split(".");
    const decode = (part: string) => Uint8Array.from(atob(part.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    expect(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, decode(signature),
      new TextEncoder().encode(`${head}.${claims}`))).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(decode(claims)))).toMatchObject({
      iss: "studio@p.iam.gserviceaccount.com", aud: "https://oauth2.googleapis.com/token", exp: now + 3600 });
  });

  it("keeps the access token until it nearly expires", async () => {
    forgetToken();
    const service = parseServiceAccount((await account()).json)!;
    let calls = 0;
    const fetcher = async () => {
      calls++;
      return new Response(JSON.stringify({ access_token: `t${calls}`, expires_in: 3600 }), { status: 200 });
    };
    expect(await accessToken(service, fetcher, now)).toBe("t1");
    expect(await accessToken(service, fetcher, now + 3000)).toBe("t1");
    expect(await accessToken(service, fetcher, now + 3500)).toBe("t2");
  });
});

describe("play analysis", () => {
  it("takes the play as text and checks its language and size", () => {
    expect(() => parseAnalyzeRequest("xx", "", "A: b")).toThrow(RequestError);
    expect(() => parseAnalyzeRequest("tr", "", "  ")).toThrow(RequestError);
    expect(() => parseAnalyzeRequest("tr", "", "x".repeat(600_001))).toThrow(RequestError);
    expect(analyzePrompt(parseAnalyzeRequest("tr", "Oyun", "## SCENE: 1\nAYŞE: Selam."))).toContain("PLAY:\n## SCENE: 1\nAYŞE: Selam.");
  });

  it("keeps the answer's shape whatever the model sends", () => {
    const analysis = normalizeAnalysis({
      summary: " Bir  aile dramı. ",
      characters: [{ role: "AYŞE", gender: "female", age: "adult", description: "Şüpheci." },
        { role: "", gender: "x" }, { role: "ALİ", gender: "robot", age: 5 }],
      relationships: ["Ayşe ile Ali evli.", 3, ""],
      scenes: [{ scene: "1", summary: "Kavga.", atmosphere: " Kimse  uyumadı. ", moods: [{ role: "ALİ", mood: "kapana kısılmış" }, { role: "AYŞE" }] }],
    });
    expect(analysis.summary).toBe("Bir aile dramı.");
    expect(analysis.characters.map((c) => [c.role, c.gender, c.age])).toEqual([["AYŞE", "female", "adult"], ["ALİ", "unknown", "unknown"]]);
    expect(analysis.relationships).toEqual(["Ayşe ile Ali evli.", "3"]);
    expect(analysis.scenes[0].moods).toEqual([{ role: "ALİ", mood: "kapana kısılmış" }]);
    expect(analysis.scenes[0].atmosphere).toBe("Kimse uyumadı.");
    expect(normalizeAnalysis(null)).toEqual({ summary: "", characters: [], relationships: [], scenes: [] });
  });
});

describe("voice answers", () => {
  it("passes an answer with audio on and refuses one without", async () => {
    const audio = JSON.stringify({ candidates: [{ content: { parts: [{ inlineData: { mimeType: "audio/wav", data: "UklGRg" } }] } }] });
    expect(await audioAnswer(new Response(audio), "vertex")).toBe(audio);
    const steps = JSON.stringify({ steps: [{ content: [{ type: "audio", data: "UklGRg", mime_type: "audio/wav" }] }] });
    expect(await audioAnswer(new Response(steps), "gemini")).toBe(steps);
    const empty = JSON.stringify({ candidates: [{ finishReason: "OTHER", content: { parts: [{ text: "Sorry" }] } }] });
    await expect(audioAnswer(new Response(empty), "vertex")).rejects.toThrow("The voice answered without audio (OTHER)");
  });
});

describe("fixing one line", () => {
  const body = {
    language: "tr", lines: [{ role: "VERA", text: "İçki için biraz erken değil mi?", notes: [] }],
    current: { emotion: "sarcastic", undertone: "" },
    wishes: ["tired", "made-up", "calmer"], request: "Yorgun ama\nhâlâ iğneleyici",
    pronunciations: [{ word: "İçki", say: "İçkı" }, { word: "Yok", say: "x" }],
  };

  it("takes known wishes, the actor's words and pronunciations of words in the line", () => {
    const wish = parseReviseRequest(body);
    expect(wish.wishes).toEqual(["tired", "calmer"]);
    expect(wish.request).toBe("Yorgun ama hâlâ iğneleyici");
    expect(wish.pronunciations).toEqual([{ word: "İçki", say: "İçkı" }]);
    const prompt = revisePrompt(wish);
    expect(prompt).toContain("1. VERA: İçki için biraz erken değil mi?");
    expect(prompt).toContain('more tired and worn out; calmer and more controlled; in the actor\'s own words: "Yorgun ama hâlâ iğneleyici"');
    expect(prompt).toContain('"emotion":"sarcastic"');
  });

  it("refuses a request that asks for nothing or more than one line", () => {
    expect(() => parseReviseRequest({ ...body, wishes: [], request: " ", pronunciations: [] })).toThrow(RequestError);
    expect(() => parseReviseRequest({ ...body, lines: [...body.lines, ...body.lines] })).toThrow(RequestError);
  });

  it("keeps the actor's pronunciations in the new direction", () => {
    const wish = parseReviseRequest(body);
    const direction = revisedDirection(wish, { lines: [{
      emotion: "tired", pace: "slow", undertone: "worn out", note: "", pronunciations: [{ word: "İçki", say: "Ickey" }],
      segments: [{ text: "İçki için biraz erken değil mi?", volume: "quiet", style: "weary, dry", pauseAfterMs: 0 }],
    }] });
    expect(direction.emotion).toBe("tired");
    expect(direction.pronunciations).toEqual([{ word: "İçki", say: "İçkı" }]);
  });
});

describe("who may prepare a script", () => {
  const now = 1_800_000_000_000;
  const premium = { month: [], kept: [], credits: 0, premiumUntil: now + 1000 };

  it("needs Premium, then gives the month's scripts, then extra scripts bought", () => {
    expect(decide({ ...premium, premiumUntil: 0 }, "a", 2, now)).toEqual({ allowed: false, reason: "premium_required" });
    const first = decide(premium, "a", 2, now);
    expect(first.allowed && first.state.month).toEqual(["a"]);
    const full = { ...premium, month: ["a", "b"] };
    expect(decide(full, "a", 2, now)).toMatchObject({ allowed: true, changed: false });
    expect(decide(full, "c", 2, now)).toEqual({ allowed: false, reason: "extra_script_needed" });
    const bought = decide({ ...full, credits: 1 }, "c", 2, now);
    expect(bought.allowed && [bought.state.kept, bought.state.credits]).toEqual([["c"], 0]);
  });

  it("keeps a script bought open even after Premium ends", () => {
    expect(decide({ ...premium, premiumUntil: 0, kept: ["c"] }, "c", 2, now)).toMatchObject({ allowed: true });
  });
});

describe("store checks", () => {
  async function appleKey() {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer);
    let binary = "";
    der.forEach((b) => (binary += String.fromCharCode(b)));
    return { key: { keyId: "K1", issuerId: "I1", privateKey: btoa(binary), bundleId: "com.memoeverything.app" },
      publicKey: pair.publicKey };
  }

  it("signs App Store requests with ES256", async () => {
    const { key, publicKey } = await appleKey();
    const [head, claims, signature] = (await appleToken(key, now)).split(".");
    const decode = (part: string) => Uint8Array.from(atob(part.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, decode(signature),
      new TextEncoder().encode(`${head}.${claims}`))).toBe(true);
    expect(JSON.parse(atob(claims.replace(/-/g, "+").replace(/_/g, "/")))).toMatchObject({
      iss: "I1", aud: "appstoreconnect-v1", bid: "com.memoeverything.app" });
  });

  it("reads an App Store transaction, from the sandbox when production does not know it", async () => {
    const { key } = await appleKey();
    const info = { transactionId: "2000", productId: "memo_extra_script", bundleId: "com.memoeverything.app" };
    const jws = `x.${btoa(JSON.stringify(info)).replace(/=+$/, "")}.y`;
    expect(jwsPayload(jws)).toEqual(info);
    const hosts: string[] = [];
    const fetcher = async (url: string) => {
      hosts.push(new URL(url).host);
      return url.includes("sandbox") ? new Response(JSON.stringify({ signedTransactionInfo: jws }))
        : new Response("", { status: 404 });
    };
    expect(await checkAppleTransaction(key, "2000", fetcher)).toEqual({
      id: "2000", product: "memo_extra_script", until: 0, paid: true });
    expect(hosts).toEqual(["api.storekit.itunes.apple.com", "api.storekit-sandbox.itunes.apple.com"]);
    const other = `x.${btoa(JSON.stringify({ ...info, bundleId: "other" })).replace(/=+$/, "")}.y`;
    await expect(checkAppleTransaction(key, "2000", async () => new Response(JSON.stringify({ signedTransactionInfo: other }))))
      .rejects.toThrow("another app");
  });
});
