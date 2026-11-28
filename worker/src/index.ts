/**
 * Memo Everything studio voices. The apps send a scene or a line with the user's Firebase ID
 * token; this Worker checks the user and their monthly allowance, asks Gemini and answers.
 *
 *   POST /v1/analyze the whole play     -> the director's reading: story, characters, relationships, scenes
 *   POST /v1/cast    a scene        -> the characters' likely genders, before the cast is chosen
 *   POST /v1/direct  a scene        -> performance notes per line and the characters' likely genders
 *   POST /v1/revise  one line and how the actor wants it -> that line's new direction
 *   POST /v1/speak   one noted line -> Gemini's answer: candidates with base64 WAV (Google Cloud) or
 *                                      PCM (older voices), or an interaction whose steps hold WAV
 *   GET  /v1/usage                  -> this month's counts and limits
 */
import { ANALYSIS_SCHEMA, analyzePrompt, MAX_PLAY_CHARS, normalizeAnalysis, parseAnalyzeRequest } from "./analysis";
import { AuthError, verifyIdToken } from "./auth";
import { CAST_SCHEMA, castPrompt, DIRECTION_SCHEMA, directorPrompt, normalizeCast, normalizeDirections, parseDirectRequest, RequestError, scriptOf } from "./director";
import { answerText, audioAnswer, DIRECTOR_MODELS, generate, interact, LEGACY_VOICE_MODELS, UpstreamError, VERTEX_VOICE_MODELS,
  vertexGenerate, VOICE_MODELS } from "./gemini";
import { Quota, QuotaKind } from "./quota";
import { parseReviseRequest, revisedDirection, revisePrompt } from "./revise";
import { legacyPrompt, parseSpeakRequest, SpeakRequest, VoiceInput, voiceInput } from "./speak";
import { accessToken, parseServiceAccount } from "./vertex";
import { APPLE_PREMIUM, AppleKey, Checked, checkAppleTransaction, checkPlayProduct, checkPlaySubscription,
  EXTRA_SCRIPT } from "./store";

export { Quota };

export interface Env {
  GEMINI_API_KEY: string;
  /** A Google Cloud service account's JSON key (role Vertex AI User); optional. */
  GCP_SERVICE_ACCOUNT_JSON?: string;
  FIREBASE_PROJECT_ID: string;
  MONTHLY_DIRECTED_LINES: string;
  MONTHLY_SPOKEN_LINES: string;
  MONTHLY_SCRIPTS: string;
  /** "true" once the store products exist: only paying users get studio voices. */
  ENFORCE_PURCHASES?: string;
  ANDROID_PACKAGE?: string;
  IOS_BUNDLE?: string;
  /** App Store Connect In-App Purchase key, for the App Store Server API. */
  APPSTORE_KEY_ID?: string;
  APPSTORE_ISSUER_ID?: string;
  APPSTORE_PRIVATE_KEY?: string;
  QUOTA: DurableObjectNamespace<Quota>;
}

const MAX_BODY_BYTES = 256 * 1024;

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...headers },
  });
}

function problem(status: number, code: string, message: string, retryAfter?: number): Response {
  return json({ error: { code, message } }, status, retryAfter ? { "Retry-After": String(retryAfter) } : {});
}

async function readBody(request: Request): Promise<unknown> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > MAX_BODY_BYTES) {
    throw new RequestError("Request is too large");
  }
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) {
    throw new RequestError("Request is too large");
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new RequestError("Body is not JSON");
  }
}

function limitFor(env: Env, kind: QuotaKind): number {
  return Number(kind === "directed" ? env.MONTHLY_DIRECTED_LINES : env.MONTHLY_SPOKEN_LINES) || 0;
}

/**
 * Refuses a script the user may not prepare: without Premium (402 premium_required), or beyond the
 * month's scripts with no extra script bought (402 extra_script_needed). While purchases are not
 * enforced, only the monthly number of scripts applies (429 script_limit).
 */
async function openScript(body: unknown, env: Env, quota: DurableObjectStub<Quota>): Promise<Response | null> {
  const limit = Number(env.MONTHLY_SCRIPTS) || 0;
  const enforce = env.ENFORCE_PURCHASES === "true";
  const decision = await quota.openScript(scriptOf(body), limit, enforce);
  if (decision.allowed) {
    return null;
  }
  if (!enforce) {
    return problem(429, "script_limit", `Studio voices can be prepared for ${limit} scripts a month`, 86_400);
  }
  return decision.reason === "premium_required"
    ? problem(402, "premium_required", "Studio voices come with Premium")
    : problem(402, "extra_script_needed", `This month's ${limit} scripts are used; an extra script can be added`);
}

/**
 * The apps send what the stores gave them (Play purchase tokens, App Store transaction ids); the
 * Worker asks the store about each, keeps when Premium ends and counts paid extra scripts once.
 * The answer lists the extra scripts it accepted, which the apps may then consume.
 */
async function entitlement(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const body = (await readBody(request)) as Record<string, unknown>;
  const platform = body?.platform;
  const list = (value: unknown) => (Array.isArray(value) ? value : [])
    .filter((v): v is string => typeof v === "string" && v.length > 0 && v.length < 2000).slice(0, 5);
  const subscriptions = list(body?.subscriptions);
  const extras = list(body?.extras);
  let check: (purchase: string, oneTime: boolean) => Promise<Checked>;
  if (platform === "android") {
    const account = parseServiceAccount(env.GCP_SERVICE_ACCOUNT_JSON);
    const packageName = env.ANDROID_PACKAGE ?? "com.memoeverything.app";
    if (!account) {
      return problem(503, "store_unavailable", "Google Play purchases cannot be checked yet");
    }
    check = (purchase, oneTime) => oneTime ? checkPlayProduct(account, packageName, EXTRA_SCRIPT, purchase)
      : checkPlaySubscription(account, packageName, purchase);
  } else if (platform === "ios") {
    if (!env.APPSTORE_KEY_ID || !env.APPSTORE_ISSUER_ID || !env.APPSTORE_PRIVATE_KEY) {
      return problem(503, "store_unavailable", "App Store purchases cannot be checked yet");
    }
    const key: AppleKey = { keyId: env.APPSTORE_KEY_ID, issuerId: env.APPSTORE_ISSUER_ID,
      privateKey: env.APPSTORE_PRIVATE_KEY, bundleId: env.IOS_BUNDLE ?? "com.memoeverything.app" };
    check = (purchase) => checkAppleTransaction(key, purchase);
  } else {
    throw new RequestError("platform must be android or ios");
  }
  if (subscriptions.length) {
    let until = 0;
    for (const purchase of subscriptions) {
      try {
        const checked = await check(purchase, false);
        if (platform === "android" || APPLE_PREMIUM.includes(checked.product)) {
          until = Math.max(until, checked.until);
        }
      } catch (error) {
        console.error("A subscription could not be checked", error);
      }
    }
    await quota.setPremium(until);
  }
  const accepted: string[] = [];
  for (const purchase of extras) {
    try {
      const checked = await check(purchase, true);
      if (checked.paid && checked.product === EXTRA_SCRIPT) {
        await quota.addCredit(checked.id);
        accepted.push(purchase);
      }
    } catch (error) {
      console.error("An extra script could not be checked", error);
    }
  }
  return json({ ...(await quota.entitlement()), accepted, enforced: env.ENFORCE_PURCHASES === "true" });
}

async function direct(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const body = await readBody(request);
  const scene = parseDirectRequest(body);
  const refused = await openScript(body, env, quota);
  if (refused) {
    return refused;
  }
  const limit = limitFor(env, "directed");
  const allowance = await quota.check("directed", scene.lines.length, limit);
  if (!allowance.allowed) {
    return problem(429, "monthly_limit", "Monthly limit for performance notes reached", 86_400);
  }
  const response = await generate(DIRECTOR_MODELS, {
    contents: [{ parts: [{ text: directorPrompt(scene) }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: DIRECTION_SCHEMA, temperature: 0.7 },
  }, env.GEMINI_API_KEY);
  let directions;
  let cast;
  try {
    const answer = JSON.parse(await answerText(response));
    directions = normalizeDirections(scene, answer);
    cast = normalizeCast(scene, answer);
  } catch (error) {
    throw error instanceof UpstreamError ? error : new UpstreamError("The director's answer could not be used", 502);
  }
  const used = await quota.add("directed", scene.lines.length);
  return json({ lines: directions, cast, usage: { directed: used, limit } });
}

/**
 * The director reads the whole play once. It comes as plain text (see analysis.ts) with its
 * language, title and script id in headers, which keeps the Worker's own work small.
 */
async function analyze(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  if (Number(request.headers.get("content-length") ?? "0") > MAX_PLAY_CHARS * 3) {
    throw new RequestError("The play is too long");
  }
  let title = "";
  try {
    title = decodeURIComponent(request.headers.get("x-memo-title") ?? "");
  } catch {
    // A title that cannot be read is left out.
  }
  const play = parseAnalyzeRequest(request.headers.get("x-memo-language"), title, await request.text());
  const refused = await openScript({ script: request.headers.get("x-memo-script") ?? undefined }, env, quota);
  if (refused) {
    return refused;
  }
  const response = await generate(DIRECTOR_MODELS, {
    contents: [{ parts: [{ text: analyzePrompt(play) }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: ANALYSIS_SCHEMA, temperature: 0.4 },
  }, env.GEMINI_API_KEY);
  try {
    return json(normalizeAnalysis(JSON.parse(await answerText(response))));
  } catch (error) {
    throw error instanceof UpstreamError ? error : new UpstreamError("The director's reading could not be used", 502);
  }
}

/** One line directed again the way the actor asked; counts as one directed line. */
async function revise(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const body = await readBody(request);
  const wish = parseReviseRequest(body);
  const refused = await openScript(body, env, quota);
  if (refused) {
    return refused;
  }
  const limit = limitFor(env, "directed");
  if (!(await quota.check("directed", 1, limit)).allowed) {
    return problem(429, "monthly_limit", "Monthly limit for performance notes reached", 86_400);
  }
  const response = await generate(DIRECTOR_MODELS, {
    contents: [{ parts: [{ text: revisePrompt(wish) }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: DIRECTION_SCHEMA, temperature: 0.7 },
  }, env.GEMINI_API_KEY);
  let line;
  try {
    line = revisedDirection(wish, JSON.parse(await answerText(response)));
  } catch (error) {
    throw error instanceof UpstreamError ? error : new UpstreamError("The director's answer could not be used", 502);
  }
  await quota.add("directed", 1);
  return json({ line });
}

/** The characters' genders only; cheap, so it counts against no allowance. */
async function cast(request: Request, env: Env): Promise<Response> {
  const scene = parseDirectRequest(await readBody(request));
  const response = await generate(DIRECTOR_MODELS, {
    contents: [{ parts: [{ text: castPrompt(scene) }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: CAST_SCHEMA, temperature: 0.2 },
  }, env.GEMINI_API_KEY);
  try {
    return json({ cast: normalizeCast(scene, JSON.parse(await answerText(response))) });
  } catch (error) {
    throw error instanceof UpstreamError ? error : new UpstreamError("The cast answer could not be used", 502);
  }
}

async function speak(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const body = await readBody(request);
  const line = parseSpeakRequest(body);
  const refused = await openScript(body, env, quota);
  if (refused) {
    return refused;
  }
  const limit = limitFor(env, "spoken");
  const allowance = await quota.check("spoken", 1, limit);
  if (!allowance.allowed) {
    return problem(429, "monthly_limit", "Monthly limit for studio voices reached", 86_400);
  }
  const input = voiceInput(line);
  const { body: answer, source } = await voice(line, input, env);
  await quota.add("spoken", 1);
  // The audio answer is passed on as it came. What the voice was asked goes along in headers, so the
  // apps can show how a line was made.
  return new Response(answer, { headers: {
    "Content-Type": "application/json; charset=utf-8",
    "X-Memo-Source": source,
    "X-Memo-Style": encodeURIComponent(input.style),
    "X-Memo-Text": encodeURIComponent(source === "legacy" ? legacyPrompt(input) : input.text),
  } });
}

/**
 * The line's audio: first through Google Cloud, which has no daily request cap; then the Gemini
 * API's 3.8 voices; then, when those are used up for the day, its older voices.
 */
/**
 * The line's audio: first through Google Cloud, which has no daily request cap; then the Gemini
 * API's 3.8 voices; then, when those are used up for the day or give no audio, its older voices.
 */
async function voice(line: SpeakRequest, input: VoiceInput, env: Env): Promise<{ body: string; source: string }> {
  const speechConfig = { voiceConfig: { prebuiltVoiceConfig: { voiceName: line.voice } } };
  const account = parseServiceAccount(env.GCP_SERVICE_ACCOUNT_JSON);
  if (account) {
    try {
      const response = await vertexGenerate(VERTEX_VOICE_MODELS, {
        contents: [{ role: "user", parts: [{ text: input.text, speechMetadata: { style: input.style } }] }],
        generationConfig: { responseModalities: ["AUDIO"], speechConfig },
      }, account.project_id, await accessToken(account));
      return { body: await audioAnswer(response, "vertex"), source: "vertex" };
    } catch (error) {
      console.error("Google Cloud voice failed, using the Gemini API", error);
    }
  }
  try {
    const response = await interact(VOICE_MODELS, (model) => ({
      model,
      input: [{ type: "user_input", content: [{ type: "text", text: input.text,
        annotations: [{ type: "speech_metadata", style: input.style }] }] }],
      response_format: { type: "audio" },
      generation_config: { speech_config: [{ voice: line.voice }] },
    }), env.GEMINI_API_KEY);
    return { body: await audioAnswer(response, "gemini"), source: "gemini" };
  } catch (error) {
    const usedUp = error instanceof UpstreamError && error.status === 429;
    const noAudio = error instanceof UpstreamError && error.message.startsWith("The voice answered without audio");
    if (!usedUp && !noAudio) {
      throw error;
    }
    // Older voices answer in generateContent's form (candidates with raw PCM), which the apps read too.
    const response = await generate(LEGACY_VOICE_MODELS, {
      contents: [{ parts: [{ text: legacyPrompt(input) }] }],
      generationConfig: { responseModalities: ["AUDIO"], speechConfig },
    }, env.GEMINI_API_KEY);
    return { body: await audioAnswer(response, "legacy"), source: "legacy" };
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true });
    }
    const routes: Record<string, string> = { "/v1/entitlement": "POST", "/v1/analyze": "POST", "/v1/cast": "POST", "/v1/revise": "POST", "/v1/direct": "POST", "/v1/speak": "POST",
      "/v1/usage": "GET" };
    const method = routes[url.pathname];
    if (!method) {
      return problem(404, "not_found", "Unknown path");
    }
    if (request.method !== method) {
      return problem(405, "method_not_allowed", `Use ${method}`);
    }
    try {
      const token = /^Bearer (.+)$/.exec(request.headers.get("authorization") ?? "")?.[1];
      if (!token) {
        return problem(401, "unauthenticated", "Sign in first");
      }
      const uid = await verifyIdToken(token, env.FIREBASE_PROJECT_ID);
      const quota = env.QUOTA.get(env.QUOTA.idFromName(uid));
      if (url.pathname === "/v1/usage") {
        const used = await quota.usage();
        return json({ used, limits: { directed: limitFor(env, "directed"), spoken: limitFor(env, "spoken"),
          scripts: Number(env.MONTHLY_SCRIPTS) || 0 } });
      }
      if (url.pathname === "/v1/analyze") {
        return await analyze(request, env, quota);
      }
      if (url.pathname === "/v1/entitlement") {
        return await entitlement(request, env, quota);
      }
      if (url.pathname === "/v1/revise") {
        return await revise(request, env, quota);
      }
      if (url.pathname === "/v1/cast") {
        return await cast(request, env);
      }
      return url.pathname === "/v1/direct" ? await direct(request, env, quota) : await speak(request, env, quota);
    } catch (error) {
      if (error instanceof AuthError) {
        return problem(401, "unauthenticated", error.message);
      }
      if (error instanceof RequestError) {
        return problem(400, "bad_request", error.message);
      }
      if (error instanceof UpstreamError) {
        return problem(error.status, error.status === 429 ? "daily_limit" : "upstream", error.message,
          error.retryAfterSeconds);
      }
      console.error(error);
      return problem(500, "internal", "Something went wrong");
    }
  },
} satisfies ExportedHandler<Env>;
