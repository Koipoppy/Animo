// Live end-to-end check against the real Lux3D API. Spends credits: run deliberately.
//   node scripts/smoke.mjs url  <imageUrl>
//   node scripts/smoke.mjs text "<description>"
//   node scripts/smoke.mjs photo <path-to-image>
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { createApp } from "../src/app.mjs";
import { loadConfig, loadEnvFile } from "../src/config.mjs";
import { createLux3dClient } from "../src/lux3d.mjs";
import { createStore } from "../src/store.mjs";

const [kind, arg, styleArg] = process.argv.slice(2);
if (!["url", "text", "photo"].includes(kind) || !arg) {
  console.error('用法: node scripts/smoke.mjs <url|text|photo> <图片链接|描述|图片路径> [plush|faithful]');
  process.exit(2);
}

loadEnvFile();
const config = loadConfig();
if (config.demoMode) {
  console.error("未配置 LUX3D_API_KEY，无法做真实冒烟测试。");
  process.exit(2);
}
// Never write smoke-test artifacts into the real data directory.
config.dataDir = fileURLToPath(new URL("../data-smoke/", import.meta.url));
const store = createStore(config.dataDir);
await store.init();
const app = createApp({ config, store, lux3d: createLux3dClient(config.lux3d) });
const server = await new Promise((resolve) => {
  const instance = app.listen(0, "127.0.0.1", () => resolve(instance));
});
const base = `http://127.0.0.1:${server.address().port}`;

const started = Date.now();
const seconds = () => `${((Date.now() - started) / 1000).toFixed(0)}s`;
let exitCode = 0;
try {
  let init;
  if (kind === "photo") {
    const form = new FormData();
    form.append("image", new Blob([await fs.readFile(arg)], { type: "image/png" }), "photo.png");
    form.append("name", "冒烟");
    if (styleArg) form.append("style", styleArg);
    init = { method: "POST", body: form };
  } else {
    const fields = kind === "url" ? { imageUrl: arg, name: "冒烟" } : { prompt: arg, name: "冒烟" };
    if (styleArg) fields.style = styleArg;
    init = { method: "POST", body: new URLSearchParams(fields) };
  }
  const created = await (await fetch(`${base}/api/v1/pets`, init)).json();
  if (!created.id) throw new Error(`创建失败: ${JSON.stringify(created)}`);
  console.log(`[${seconds()}] 已创建任务 ${created.id}（${kind}）`);

  let lastStage = "";
  for (;;) {
    const job = await (await fetch(`${base}/api/v1/pets/${created.id}`)).json();
    if (job.stage !== lastStage) {
      lastStage = job.stage;
      console.log(`[${seconds()}] ${job.status} · ${job.stage} · ${(job.progress * 100).toFixed(0)}%`);
    }
    if (job.status === "failed") throw new Error(`任务失败: ${job.error}`);
    if (job.status === "succeeded") {
      console.log(`[${seconds()}] 完成，模型统计:`, JSON.stringify(job.stats));
      const model = await fetch(`${base}${job.modelUrl}`);
      const bytes = Buffer.from(await model.arrayBuffer());
      const label = `${kind}${styleArg ? "-" + styleArg : ""}`;
      const out = fileURLToPath(new URL(`../data-smoke/${label}-result.glb`, import.meta.url));      await fs.writeFile(out, bytes);
      console.log(`GLB ${bytes.length} 字节 → ${out}`);
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
} catch (error) {
  console.error("冒烟测试失败:", error.message);
  exitCode = 1;
} finally {
  await app.locals.jobs.idle();
  server.close();
}
process.exit(exitCode);
