/**
 * Memo Everything studio voices. The apps send a scene or a line with the user's Firebase ID
 * token; this Worker checks the user and their monthly allowance, asks Gemini and answers.
 *
 *   POST /v1/direct  a scene        -> performance notes per line and the characters' likely genders
 *   POST /v1/speak   one noted line -> Gemini's interaction, whose steps hold the line as base64 WAV
 *   GET  /v1/usage                  -> this month's counts and limits
 */
import { AuthError, verifyIdToken } from "./auth";
import { DIRECTION_SCHEMA, directorPrompt, normalizeCast, normalizeDirections, parseDirectRequest, RequestError } from "./director";
import { answerText, DIRECTOR_MODELS, generate, interact, UpstreamError, VOICE_MODELS } from "./gemini";
import { Quota, QuotaKind } from "./quota";
import { parseSpeakRequest, voiceInput } from "./speak";

export { Quota };

export interface Env {
  GEMINI_API_KEY: string;
  FIREBASE_PROJECT_ID: string;
  MONTHLY_DIRECTED_LINES: string;
  MONTHLY_SPOKEN_LINES: string;
  QUOTA: DurableObjectNamespace<Quota>;
}

const MAX_BODY_BYTES = 64 * 1024;

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

async function direct(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const scene = parseDirectRequest(await readBody(request));
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

async function speak(request: Request, env: Env, quota: DurableObjectStub<Quota>): Promise<Response> {
  const line = parseSpeakRequest(await readBody(request));
  const limit = limitFor(env, "spoken");
  const allowance = await quota.check("spoken", 1, limit);
  if (!allowance.allowed) {
    return problem(429, "monthly_limit", "Monthly limit for studio voices reached", 86_400);
  }
  const { text, style } = voiceInput(line);
  const response = await interact(VOICE_MODELS, (model) => ({
    model,
    input: [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style }] }] }],
    response_format: { type: "audio" },
    generation_config: { speech_config: [{ voice: line.voice }] },
  }), env.GEMINI_API_KEY);
  await quota.add("spoken", 1);
  // The audio answer is large; it is passed through unread to keep the Worker within its CPU budget.
  return new Response(response.body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true });
    }
    const routes: Record<string, string> = { "/v1/direct": "POST", "/v1/speak": "POST", "/v1/usage": "GET" };
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
        return json({ used, limits: { directed: limitFor(env, "directed"), spoken: limitFor(env, "spoken") } });
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
