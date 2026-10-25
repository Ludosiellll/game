// «Спросить тренера»: a signed-in user's question about their own training, answered by Gemini with the summary of the
// training the app sends. The key stays here (the GEMINI_API_KEY secret), never in the app; verify_jwt stays on, so only
// a signed-in user of this project reaches this code. Nothing is stored.

const MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-3.5-flash-lite";
const MAX_CONTEXT = 40_000;
const MAX_TURN = 2_000;
const MAX_TURNS = 12;

const SYSTEM = `Ты — тренер в приложении «Форма» (силовые в зале и бег). Отвечай по-русски, коротко и по делу: 2–6 предложений или короткий список, без воды и без лишних вступлений. Обращайся на «ты».

Опирайся на данные человека ниже: его план, последние тренировки, бег, самочувствие. Не выдумывай цифры, которых там нет; не хватает данных — так и скажи.

План строит и меняет само приложение по своим правилам: ты его не меняешь и не предлагаешь другой. Если человеку нужно что-то поменять, скажи, где это сделать в приложении: заменить упражнение, подходы, повторы, отдых — «План», нажать на упражнение; пауза (отпуск, болезнь) — «План → Пауза»; плохо себя чувствует — «Как ты сегодня?» на «Сегодня» («Так себе» облегчает тренировку, «Болею» — день отдыха); цель, дни, только зал или только бег — «Ещё → Цель, дни и данные»; ошибся с оценкой тренировки или с кругом — «Ещё → История»; советы по плану зала — «План → Идеальный план».

Слова приложения объясняй просто (запас — сколько ещё повторов мог сделать; разгрузка; темп цели; VDOT — форма бега и т. п.); описание экранов и словарь — в данных ниже.

Боль, травма, головокружение, болезнь, давление, сердце: диагнозов не ставь, советуй остановить нагрузку и обратиться к врачу; можно напомнить про «Болею». Про питание и сон — общие безопасные советы, без добавок и лекарств.`;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: "no_key" }, 503);
  let body: { context?: unknown; messages?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_request" }, 400);
  }
  const context = String(body.context ?? "").slice(0, MAX_CONTEXT);
  const turns = Array.isArray(body.messages) ? body.messages.slice(-MAX_TURNS) : [];
  const contents = turns
    .filter((m) => typeof m?.text === "string" && m.text.trim() !== "")
    .map((m) => ({ role: m.role === "model" ? "model" : "user", parts: [{ text: String(m.text).slice(0, MAX_TURN) }] }));
  if (contents.length === 0 || contents[contents.length - 1].role !== "user") return json({ error: "bad_request" }, 400);

  let res: Response;
  try {
    res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: `${SYSTEM}\n\n---\n${context}` }] },
        contents,
        // Room for the model's own short thinking, which counts toward the limit.
        generationConfig: { maxOutputTokens: 1500 },
      }),
    });
  } catch {
    return json({ error: "provider" }, 502);
  }
  if (!res.ok) return json({ error: res.status === 429 ? "busy" : "provider", status: res.status }, 502);
  const data = await res.json();
  const parts = data?.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p: { text?: string; thought?: boolean }) => (p.thought ? "" : p.text ?? "")).join("").trim();
  if (!text) return json({ error: "empty" }, 502);
  return json({ text });
});
