// Lists the speech models this Gemini key can use. Uses no generation quota. Run on CI.
const response = await fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
  { headers: { "X-goog-api-key": process.env.GEMINI_API_KEY } });
const body = await response.json();
for (const model of body.models ?? []) {
  if (/tts|speech|audio/i.test(model.name)) {
    console.log(model.name, "|", (model.supportedGenerationMethods ?? []).join(","), "|", model.description?.slice(0, 80));
  }
}
console.log("status", response.status, "models", (body.models ?? []).length);
