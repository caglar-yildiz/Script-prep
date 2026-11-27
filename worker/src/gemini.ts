/** Calls Gemini with retries on overload and a fallback model when one stays busy. */

const BASE = "https://generativelanguage.googleapis.com/v1beta/";

export const DIRECTOR_MODELS = ["gemini-3.5-flash-lite", "gemini-flash-lite-latest"];
export const VOICE_MODELS = ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"];

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
  return send(models, (model) => ({ path: `models/${model}:generateContent`, body }), apiKey, fetcher, sleep);
}

/**
 * Speech through the Interactions API, the one where Gemini 3.8 voices follow a turn's style
 * (speech_metadata) instead of hearing the directions as part of the text.
 */
export async function interact(models: string[], body: (model: string) => unknown, apiKey: string,
  fetcher: Fetcher = fetch, sleep: Sleeper = wait): Promise<Response> {
  return send(models, (model) => ({ path: "interactions", body: body(model) }), apiKey, fetcher, sleep);
}

async function send(models: string[], request: (model: string) => { path: string; body: unknown }, apiKey: string,
  fetcher: Fetcher, sleep: Sleeper): Promise<Response> {
  let last: UpstreamError = new UpstreamError("No model answered", 503);
  for (const model of models) {
    for (let attempt = 0; attempt < 3; attempt++) {
      const { path, body } = request(model);
      const response = await fetcher(BASE + path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-goog-api-key": apiKey },
        body: JSON.stringify(body),
      });
      if (response.ok) {
        return response;
      }
      const text = await response.text();
      if (response.status === 429 && isDailyLimit(text)) {
        // This model is done for the day; the next one has its own quota.
        last = new UpstreamError("Daily limit reached", 429, 3600);
        break;
      }
      if (response.status === 429 || response.status >= 500) {
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
