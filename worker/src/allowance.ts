/**
 * Who may prepare studio voices for which script. Premium gives a number of scripts each month;
 * an extra script bought once belongs to one script for good. Kept apart from the Durable Object
 * so the rules can be tested on their own.
 */

export interface AllowanceState {
  /** Scripts opened this month with Premium's monthly allowance. */
  month: string[];
  /** Scripts opened with an extra script bought; they stay open. */
  kept: string[];
  /** Extra scripts bought and not used yet. */
  credits: number;
  /** When Premium ends, in milliseconds since 1970; 0 without Premium. */
  premiumUntil: number;
}

export type Decision =
  | { allowed: true; state: AllowanceState; changed: boolean }
  | { allowed: false; reason: "premium_required" | "extra_script_needed" };

/** The decision for a script and the state that follows from it. */
export function decide(state: AllowanceState, script: string, monthly: number, now: number): Decision {
  if (state.kept.includes(script) || state.month.includes(script)) {
    return { allowed: true, state, changed: false };
  }
  if (state.premiumUntil <= now) {
    return { allowed: false, reason: "premium_required" };
  }
  if (state.month.length < monthly) {
    return { allowed: true, state: { ...state, month: [...state.month, script] }, changed: true };
  }
  if (state.credits > 0) {
    return { allowed: true, state: { ...state, kept: [...state.kept, script], credits: state.credits - 1 }, changed: true };
  }
  return { allowed: false, reason: "extra_script_needed" };
}
