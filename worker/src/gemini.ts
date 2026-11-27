/** Calls Gemini with retries on overload and a fallback model when one stays busy. */

const BASE = "https://generativelanguage.googleapis.com/v1beta/";
const VERTEX = "https://aiplatform.googleapis.com/v1/projects/";

export const DIRECTOR_MODELS = ["gemini-3.5-flash-lite", "gemini-flash-lite-latest"];
export const VOICE_MODELS = ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"];
/**
 * The same voices through Google Cloud (Vertex AI), billed to the Cloud account. They have no daily
 * request cap like the Gemini API's (120 requests in a row passed on CI), so they come first.
 */
export const VERTEX_VOICE_MODELS = ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"];
/**
 * Older speech models, each with a daily quota of its own, used when the ones above are used up
 * for the day. They take no speech_metadata, so the delivery is written before the line.
 */
export const LEGACY_VOICE_MODELS = ["gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts",
  "gemini-2.5-pro-preview-tts"];

export class UpstreamError extends Error {
  constructor(message: string, readonly status: number, readonly retryAfterSeconds?: number) {
    super(message);
  }
}

export type Fetcher = (input: string, init: RequestInit) => Promise<Response>;
export type Sleeper = (ms: number) => Promise<void>;

const wait: Sleeper = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Whether a 429 means "too many today" (wait for tomorrow) rather than "too fast" (retry soon). */
function isDailyLimit(body: string): boolean {
  return /PerDay|per day/i.test(body);
}

/**
 * Sends one request, trying each model in turn and retrying busy answers a few times. Returns the
 * successful response unread, so a large audio answer can be streamed straight to the app.
 */
export async function generate(models: string[], body: unknown, apiKey: string, fetcher: Fetcher = fetch,
  sleep: Sleeper = wait): Promise<Response> {
  return send(models, (model) => ({ url: `${BASE}models/${model}:generateContent`, headers: keyHeader(apiKey), body }),
    fetcher, sleep);
}

/** generateContent through Google Cloud (Vertex AI) in the global location, with an OAuth token. */
export async function vertexGenerate(models: string[], body: unknown, project: string, token: string,
  fetcher: Fetcher = fetch, sleep: Sleeper = wait): Promise<Response> {
  return send(models, (model) => ({
    url: `${VERTEX}${project}/locations/global/publishers/google/models/${model}:generateContent`,
    headers: { Authorization: `Bearer ${token}` },
    body,
  }), fetcher, sleep);
}

function keyHeader(apiKey: string): Record<string, string> {
  return { "X-goog-api-key": apiKey };
}

/**
 * Speech through the Interactions API, the one where Gemini 3.8 voices follow a turn's style
 * (speech_metadata) instead of hearing the directions as part of the text.
 */
export async function interact(models: string[], body: (model: string) => unknown, apiKey: string,
  fetcher: Fetcher = fetch, sleep: Sleeper = wait): Promise<Response> {
  return send(models, (model) => ({ url: `${BASE}interactions`, headers: keyHeader(apiKey), body: body(model) }),
    fetcher, sleep);
}

interface Call {
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

async function send(models: string[], request: (model: string) => Call, fetcher: Fetcher,
  sleep: Sleeper): Promise<Response> {
  let last: UpstreamError = new UpstreamError("No model answered", 503);
  for (const model of models) {
    for (let attempt = 0; attempt < 3; attempt++) {
      const { url, headers, body } = request(model);
      const response = await fetcher(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...headers },
        body: JSON.stringify(body),
      });
      if (response.ok) {
        return response;
      }
      const text = await response.text();
      if (response.status === 429 && isDailyLimit(text)) {
        // This model is done for the day; the next one has its own quota.
        // Google's message names the quota; it is logged so a limit can be told from another.
        console.error(`${model}: ${text.slice(0, 600)}`);
        last = new UpstreamError(`Daily limit reached: ${quotaName(text)}`, 429, 3600);
        break;
      }
      if (response.status === 429 || response.status >= 500) {
        console.error(`${model} busy: ${response.status} ${text.slice(0, 300)}`);
        last = new UpstreamError(`Model busy (${response.status})`, 503, 30);
        await sleep(500 * 3 ** attempt);
        continue;
      }
      // Anything else (bad request, blocked content) will not get better with retries.
      throw new UpstreamError(`Gemini refused the request (${response.status})`, 502);
    }
  }
  throw last;
}

/** The JSON text of a successful text answer. */
export async function answerText(response: Response): Promise<string> {
  const body = (await response.json()) as {
    candidates?: { content?: { parts?: { text?: string }[] }; finishReason?: string }[];
  };
  const parts = body.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p) => p.text ?? "").join("");
  if (!text) {
    throw new UpstreamError("Empty answer", 502);
  }
  return text;
}

/** The quota a 429 answer names, such as "generate_requests_per_model_per_day, limit: 100". */
function quotaName(body: string): string {
  const metric = /metric: ([\w./-]+)/.exec(body)?.[1] ?? "";
  const limit = /limit: (\d+)/.exec(body)?.[1] ?? "";
  return [metric.split("/").pop(), limit && `limit ${limit}`].filter(Boolean).join(", ") || body.slice(0, 160);
}
