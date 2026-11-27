import { DurableObject } from "cloudflare:workers";

export type QuotaKind = "directed" | "spoken";

export interface QuotaResult {
  allowed: boolean;
  used: number;
  limit: number;
}

/** The month a count belongs to, e.g. "2026-11"; counts start again each month (UTC). */
export function monthKey(now = new Date()): string {
  return now.toISOString().slice(0, 7);
}

/** One per user: how many lines they had directed and voiced this month. */
export class Quota extends DurableObject {
  async check(kind: QuotaKind, amount: number, limit: number): Promise<QuotaResult> {
    const used = (await this.ctx.storage.get<number>(`${monthKey()}:${kind}`)) ?? 0;
    return { allowed: used + amount <= limit, used, limit };
  }

  async add(kind: QuotaKind, amount: number): Promise<number> {
    const key = `${monthKey()}:${kind}`;
    const used = ((await this.ctx.storage.get<number>(key)) ?? 0) + amount;
    await this.ctx.storage.put(key, used);
    return used;
  }

  /**
   * Lets a script use studio voices this month: one already opened this month always may; a new one
   * only while fewer than `limit` scripts have been opened. Returns the scripts opened this month.
   */
  async openScript(script: string, limit: number): Promise<{ allowed: boolean; scripts: number }> {
    const key = `${monthKey()}:scripts`;
    const scripts = (await this.ctx.storage.get<string[]>(key)) ?? [];
    if (scripts.includes(script)) {
      return { allowed: true, scripts: scripts.length };
    }
    if (scripts.length >= limit) {
      return { allowed: false, scripts: scripts.length };
    }
    scripts.push(script);
    await this.ctx.storage.put(key, scripts);
    return { allowed: true, scripts: scripts.length };
  }

  async usage(): Promise<Record<QuotaKind | "scripts", number>> {
    const month = monthKey();
    return {
      directed: (await this.ctx.storage.get<number>(`${month}:directed`)) ?? 0,
      spoken: (await this.ctx.storage.get<number>(`${month}:spoken`)) ?? 0,
      scripts: ((await this.ctx.storage.get<string[]>(`${month}:scripts`)) ?? []).length,
    };
  }
}
