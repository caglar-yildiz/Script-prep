/**
 * Checks purchases with the stores, so studio voices go to paying users only.
 * - Google Play: the Play Developer API with the service account (it needs access to the app in
 *   the Play Console), for the Premium subscription and the one-time extra script.
 * - App Store: the App Store Server API with an In-App Purchase key (ES256 JWT).
 */
import { accessToken, PLAY_SCOPE, ServiceAccount } from "./vertex";

type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

/** A store's answer about one purchase. */
export interface Checked {
  /** A stable id of the purchase, so an extra script is counted once. */
  id: string;
  product: string;
  /** For a subscription: when it ends (ms); 0 when it is not active. */
  until: number;
  /** For a one-time product: whether it is paid and not refunded. */
  paid: boolean;
}

export const PLAY_PREMIUM = "memo_premium";
export const EXTRA_SCRIPT = "memo_extra_script";
export const APPLE_PREMIUM = ["memo_premium_monthly", "memo_premium_yearly"];

const PLAY = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";

export async function checkPlaySubscription(account: ServiceAccount, packageName: string, token: string,
  fetcher: Fetcher = fetch): Promise<Checked> {
  const response = await fetcher(`${PLAY}${packageName}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`,
    { headers: { Authorization: `Bearer ${await accessToken(account, fetch, undefined, PLAY_SCOPE)}` } });
  if (!response.ok) {
    throw new Error(`Google Play refused the subscription (${response.status})`);
  }
  const answer = (await response.json()) as {
    subscriptionState?: string; latestOrderId?: string;
    lineItems?: { productId?: string; expiryTime?: string }[];
  };
  const active = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(answer.subscriptionState ?? "");
  const item = (answer.lineItems ?? []).find((i) => i.productId === PLAY_PREMIUM);
  return {
    id: answer.latestOrderId ?? token.slice(0, 40), product: PLAY_PREMIUM,
    until: active && item?.expiryTime ? Date.parse(item.expiryTime) : 0, paid: active,
  };
}

export async function checkPlayProduct(account: ServiceAccount, packageName: string, product: string, token: string,
  fetcher: Fetcher = fetch): Promise<Checked> {
  const response = await fetcher(
    `${PLAY}${packageName}/purchases/products/${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}`,
    { headers: { Authorization: `Bearer ${await accessToken(account, fetch, undefined, PLAY_SCOPE)}` } });
  if (!response.ok) {
    throw new Error(`Google Play refused the purchase (${response.status})`);
  }
  const answer = (await response.json()) as { purchaseState?: number; orderId?: string };
  return { id: answer.orderId ?? token.slice(0, 40), product, until: 0, paid: answer.purchaseState === 0 };
}

export interface AppleKey {
  keyId: string;
  issuerId: string;
  privateKey: string;
  bundleId: string;
}

function base64Url(bytes: Uint8Array | string): string {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let binary = "";
  data.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The App Store Server API's bearer token: an ES256 JWT for five minutes. */
export async function appleToken(key: AppleKey, nowSeconds = Math.floor(Date.now() / 1000)): Promise<string> {
  const der = Uint8Array.from(atob(key.privateKey.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0));
  const signer = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const head = base64Url(JSON.stringify({ alg: "ES256", kid: key.keyId, typ: "JWT" }));
  const claims = base64Url(JSON.stringify({ iss: key.issuerId, iat: nowSeconds, exp: nowSeconds + 300,
    aud: "appstoreconnect-v1", bid: key.bundleId }));
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, signer,
    new TextEncoder().encode(`${head}.${claims}`));
  return `${head}.${claims}.${base64Url(new Uint8Array(signature))}`;
}

/** The middle of a JWS, read as JSON. The answer comes straight from Apple over TLS. */
export function jwsPayload(jws: string): Record<string, unknown> {
  const part = jws.split(".")[1] ?? "";
  const text = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
  return JSON.parse(new TextDecoder().decode(Uint8Array.from(text, (c) => c.charCodeAt(0))));
}

export async function checkAppleTransaction(key: AppleKey, transactionId: string, fetcher: Fetcher = fetch,
  now = Date.now()): Promise<Checked> {
  const token = await appleToken(key);
  let response: Response | null = null;
  // Purchases made while testing live in the sandbox.
  for (const host of ["api.storekit.itunes.apple.com", "api.storekit-sandbox.itunes.apple.com"]) {
    response = await fetcher(`https://${host}/inApps/v1/transactions/${encodeURIComponent(transactionId)}`,
      { headers: { Authorization: `Bearer ${token}` } });
    if (response.status !== 404) {
      break;
    }
  }
  if (!response || !response.ok) {
    throw new Error(`The App Store refused the purchase (${response?.status})`);
  }
  const info = jwsPayload(((await response.json()) as { signedTransactionInfo?: string }).signedTransactionInfo ?? "");
  if (info.bundleId !== key.bundleId) {
    throw new Error("The purchase is for another app");
  }
  const refunded = typeof info.revocationDate === "number";
  const expires = typeof info.expiresDate === "number" ? info.expiresDate : 0;
  return {
    id: String(info.transactionId ?? transactionId), product: String(info.productId ?? ""),
    until: !refunded && expires > now ? expires : 0, paid: !refunded,
  };
}
