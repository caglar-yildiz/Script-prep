/**
 * Checks Firebase ID tokens (the apps' anonymous or Google sign-in), so only real app users
 * reach Gemini. Follows Firebase's rules for verifying ID tokens without the Admin SDK.
 */

const KEYS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

interface Jwk extends JsonWebKey {
  kid: string;
}

let cachedKeys: { keys: Map<string, CryptoKey>; expires: number } | null = null;

export class AuthError extends Error {}

function base64UrlDecode(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function decodeJson(part: string): Record<string, unknown> {
  return JSON.parse(new TextDecoder().decode(base64UrlDecode(part)));
}

export type KeyLoader = () => Promise<Map<string, CryptoKey>>;

/** Google's current signing keys, cached for as long as Google allows. */
export const loadGoogleKeys: KeyLoader = async () => {
  const now = Date.now();
  if (cachedKeys && cachedKeys.expires > now) {
    return cachedKeys.keys;
  }
  const response = await fetch(KEYS_URL);
  if (!response.ok) {
    throw new AuthError("Could not load signing keys");
  }
  const body = (await response.json()) as { keys: Jwk[] };
  const keys = new Map<string, CryptoKey>();
  for (const jwk of body.keys) {
    keys.set(jwk.kid, await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false, ["verify"]));
  }
  const maxAge = /max-age=(\d+)/.exec(response.headers.get("cache-control") ?? "");
  cachedKeys = { keys, expires: now + (maxAge ? Number(maxAge[1]) * 1000 : 3_600_000) };
  return keys;
};

/** Returns the user id of a valid token for this Firebase project, or throws AuthError. */
export async function verifyIdToken(token: string, projectId: string, loadKeys: KeyLoader = loadGoogleKeys,
  nowSeconds = Math.floor(Date.now() / 1000)): Promise<string> {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new AuthError("Malformed token");
  }
  let header: Record<string, unknown>;
  let payload: Record<string, unknown>;
  try {
    header = decodeJson(parts[0]);
    payload = decodeJson(parts[1]);
  } catch {
    throw new AuthError("Malformed token");
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    throw new AuthError("Unexpected algorithm");
  }
  const key = (await loadKeys()).get(header.kid);
  if (!key) {
    throw new AuthError("Unknown key");
  }
  const signed = new TextEncoder().encode(parts[0] + "." + parts[1]);
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, base64UrlDecode(parts[2]), signed);
  if (!valid) {
    throw new AuthError("Bad signature");
  }
  const skew = 300;
  if (payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}`) {
    throw new AuthError("Wrong project");
  }
  if (typeof payload.exp !== "number" || payload.exp + skew < nowSeconds) {
    throw new AuthError("Expired");
  }
  if (typeof payload.iat !== "number" || payload.iat - skew > nowSeconds) {
    throw new AuthError("Issued in the future");
  }
  if (typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > 128) {
    throw new AuthError("No user");
  }
  return payload.sub;
}
