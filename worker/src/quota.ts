import { DurableObject } from "cloudflare:workers";
import { AllowanceState, decide, Decision } from "./allowance";

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

  private async allowance(): Promise<AllowanceState> {
    return {
      month: (await this.ctx.storage.get<string[]>(`${monthKey()}:scripts`)) ?? [],
      kept: (await this.ctx.storage.get<string[]>("kept")) ?? [],
      credits: (await this.ctx.storage.get<number>("credits")) ?? 0,
      premiumUntil: (await this.ctx.storage.get<number>("premiumUntil")) ?? 0,
    };
  }

  /**
   * Lets a script use studio voices. With purchases enforced the rules of allowance.ts apply;
   * without, any signed-in user may open up to `monthly` scripts a month (while testing).
   */
  async openScript(script: string, monthly: number, enforce: boolean): Promise<Decision> {
    const state = await this.allowance();
    const decision = enforce ? decide(state, script, monthly, Date.now())
      : decide({ ...state, premiumUntil: Number.MAX_SAFE_INTEGER, credits: 0 }, script, monthly, 0);
    if (decision.allowed && decision.changed) {
      await this.ctx.storage.put(`${monthKey()}:scripts`, decision.state.month);
      await this.ctx.storage.put("kept", decision.state.kept);
      if (enforce) {
        await this.ctx.storage.put("credits", decision.state.credits);
      }
    }
    return decision;
  }

  /** What the stores said about the user's Premium: until when it runs (0 when it does not). */
  async setPremium(until: number): Promise<void> {
    await this.ctx.storage.put("premiumUntil", until);
  }

  /** Counts a paid extra script once, by its purchase id; true when it was new. */
  async addCredit(purchase: string): Promise<boolean> {
    const redeemed = (await this.ctx.storage.get<string[]>("redeemed")) ?? [];
    if (redeemed.includes(purchase)) {
      return false;
    }
    await this.ctx.storage.put("redeemed", [...redeemed, purchase].slice(-500));
    await this.ctx.storage.put("credits", ((await this.ctx.storage.get<number>("credits")) ?? 0) + 1);
    return true;
  }

  async entitlement(): Promise<{ premiumUntil: number; credits: number; kept: number }> {
    const state = await this.allowance();
    return { premiumUntil: state.premiumUntil, credits: state.credits, kept: state.kept.length };
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
