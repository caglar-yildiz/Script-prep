/**
 * Google Cloud access for the Worker: a service account (secret GCP_SERVICE_ACCOUNT_JSON, its JSON
 * key) signs a short JWT, which Google exchanges for an OAuth access token. The token is kept in
 * memory until shortly before it expires.
 */

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
}

type Fetcher = (input: string, init: RequestInit) => Promise<Response>;

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/cloud-platform";

/** The service account from its JSON key, or null when the Worker has none. */
export function parseServiceAccount(json: string | undefined): ServiceAccount | null {
  if (!json) {
    return null;
  }
  try {
    const value = JSON.parse(json) as Partial<ServiceAccount>;
    return value.client_email && value.private_key && value.project_id ? (value as ServiceAccount) : null;
  } catch {
    return null;
  }
}

function base64Url(bytes: Uint8Array | string): string {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let binary = "";
  data.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function privateKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

/** The signed request for an access token (RFC 7523), valid for an hour. */
export async function signedAssertion(account: ServiceAccount, nowSeconds: number): Promise<string> {
  const head = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat: nowSeconds, exp: nowSeconds + 3600,
  }));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", await privateKey(account.private_key),
    new TextEncoder().encode(`${head}.${claims}`));
  return `${head}.${claims}.${base64Url(new Uint8Array(signature))}`;
}

let cached: { email: string; token: string; expires: number } | null = null;

export async function accessToken(account: ServiceAccount, fetcher: Fetcher = fetch,
  nowSeconds = Math.floor(Date.now() / 1000)): Promise<string> {
  if (cached && cached.email === account.client_email && nowSeconds < cached.expires - 120) {
    return cached.token;
  }
  const assertion = await signedAssertion(account, nowSeconds);
  const response = await fetcher(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${assertion}`,
  });
  if (!response.ok) {
    throw new Error(`Google refused the service account (${response.status})`);
  }
  const answer = (await response.json()) as { access_token?: string; expires_in?: number };
  if (!answer.access_token) {
    throw new Error("No access token");
  }
  cached = { email: account.client_email, token: answer.access_token, expires: nowSeconds + (answer.expires_in ?? 3600) };
  return answer.access_token;
}

/** Forgets the kept token, for tests. */
export function forgetToken(): void {
  cached = null;
}
