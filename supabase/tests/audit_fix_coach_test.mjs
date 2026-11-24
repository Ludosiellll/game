// The coach function (../functions/coach/index.ts) with its providers stubbed: run as `node supabase/tests/audit_fix_coach_test.mjs`
// (Node 22.18+ reads the TypeScript as it is). Finding coach-provider-chain-no-timeout, 24 November: a DeepSeek try that
// stalls must not take Gemini's turn — the phone waits 90 s for the whole answer (COACH_WAIT_MS in the app's Cloud.kt).
import assert from "node:assert/strict";

// The function's clock runs 100 times faster: its limits of tens of seconds pass in a fraction of one.
const SPEED = 100;
const realNow = Date.now.bind(Date);
const realSetTimeout = globalThis.setTimeout;
const t0 = realNow();
Date.now = () => t0 + (realNow() - t0) * SPEED;
globalThis.setTimeout = (fn, ms = 0, ...rest) => realSetTimeout(fn, ms / SPEED, ...rest);

/** The phone's wait for an answer, in the function's time. */
const PHONE_MS = 90_000;

const env = { GEMINI_API_KEY: "g1", GEMINI_API_KEY2: "g2", DEEPSEEK_API_KEY: "d", DEEPSEEK_USERS: "user-1" };
let handler;
globalThis.Deno = { env: { get: (k) => env[k] }, serve: (h) => { handler = h; } };

/** What each provider does in a case: "stall" (holds the request until it is aborted), a status, or an answer. */
let behave = {};
const tried = [];

function stall(signal) {
  return new Promise((_, reject) => {
    if (!signal) return; // nothing can end it: the request hangs for good
    signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
  });
}

globalThis.fetch = async (url, init = {}) => {
  const u = String(url);
  if (u.includes("generativelanguage")) {
    const key = init.headers["x-goog-api-key"];
    tried.push(`gemini:${key}`);
    const b = behave[key] ?? 200;
    if (b === "stall") return stall(init.signal);
    if (b !== 200) return new Response("{}", { status: b });
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: `Ответ Gemini (${key})` }] } }] }), { status: 200 });
  }
  if (u.includes("chat/completions")) {
    const model = JSON.parse(init.body).model;
    tried.push(`deepseek:${model}`);
    const b = behave[model] ?? 200;
    if (b === "stall") return stall(init.signal);
    if (b !== 200) return new Response("{}", { status: b });
    return new Response(JSON.stringify({ choices: [{ message: { content: `Ответ DeepSeek (${model})` } }], usage: { prompt_tokens: 10, completion_tokens: 5 } }), { status: 200 });
  }
  throw new Error(`unexpected fetch ${u}`);
};

await import("../functions/coach/index.ts");
assert.equal(typeof handler, "function", "the function serves a handler");

function ask() {
  const payload = Buffer.from(JSON.stringify({ role: "authenticated", sub: "user-1" })).toString("base64url");
  return new Request("http://localhost/coach", {
    method: "POST",
    headers: { authorization: `Bearer h.${payload}.s`, "content-type": "application/json" },
    body: JSON.stringify({ context: "сводка", messages: [{ role: "user", text: "как бежать сегодня?" }], tools: [] }),
  });
}

/** The function's answer to one question, and when it came in the function's time; null — none before the phone gave up long since. */
async function answer() {
  tried.length = 0;
  const from = Date.now();
  const res = await Promise.race([handler(ask()), new Promise((r) => realSetTimeout(() => r(null), (3 * PHONE_MS) / SPEED))]);
  return { res, ms: Date.now() - from };
}

const cases = [];
function test(name, f) { cases.push([name, f]); }

test("a stalled DeepSeek leaves Gemini its turn before the phone gives up", async () => {
  behave = { "deepseek-v4.1-flash": "stall", "deepseek-v4-flash": "stall" };
  const { res, ms } = await answer();
  assert.ok(res, `no answer at all within ${3 * PHONE_MS / 1000} s`);
  assert.ok(ms < PHONE_MS - 5_000, `answered after ${ms} ms, the phone waits ${PHONE_MS}`);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).text, "Ответ Gemini (g1)");
  // The seller's second model sits behind the same stalled gateway: Gemini answers next.
  assert.deepEqual(tried, ["deepseek:deepseek-v4.1-flash", "gemini:g1"]);
});

test("DeepSeek refused (429): its second model, then Gemini, then Gemini's second key on 429 — as before", async () => {
  behave = { "deepseek-v4.1-flash": 429, "deepseek-v4-flash": 503, g1: 429 };
  const { res, ms } = await answer();
  assert.ok(res && ms < PHONE_MS - 5_000, `answered after ${ms} ms`);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).text, "Ответ Gemini (g2)");
  assert.deepEqual(tried, ["deepseek:deepseek-v4.1-flash", "deepseek:deepseek-v4-flash", "gemini:g1", "gemini:g2"]);
});

test("DeepSeek answers: Gemini is not asked", async () => {
  behave = {};
  const { res } = await answer();
  assert.equal(res.status, 200);
  assert.equal((await res.json()).text, "Ответ DeepSeek (deepseek-v4.1-flash)");
  assert.deepEqual(tried, ["deepseek:deepseek-v4.1-flash"]);
});

test("every provider stalls: an error while the phone still listens, not silence", async () => {
  behave = { "deepseek-v4.1-flash": "stall", "deepseek-v4-flash": "stall", g1: "stall", g2: "stall" };
  const { res, ms } = await answer();
  assert.ok(res, "no answer at all");
  assert.ok(ms < PHONE_MS - 5_000, `answered after ${ms} ms`);
  assert.equal(res.status, 502);
});

let failed = 0;
for (const [name, f] of cases) {
  try {
    await f();
    console.log(`ok - ${name}`);
  } catch (e) {
    failed++;
    console.log(`not ok - ${name}\n  ${e?.message ?? e}`);
  }
}
if (failed > 0) {
  console.log(`${failed} of ${cases.length} failed`);
  process.exit(1);
}
console.log(`all ${cases.length} coach function tests passed`);
process.exit(0);
