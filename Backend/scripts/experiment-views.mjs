// Compares 3D pipelines on one picture: single view vs. four-view multi-image.
//   node scripts/experiment-views.mjs <imagePath> <label> [--restyle]
// Writes data-smoke/<label>-views.glb and <label>-fourview.json (the generated view URLs).
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { loadConfig, loadEnvFile } from "../src/config.mjs";
import { inspectGlb } from "../src/glb.mjs";
import { normalizeImage } from "../src/image.mjs";
import { createLux3dClient, pickFourViewUrls, pickGlbUrl, pickImageUrl } from "../src/lux3d.mjs";

const [imagePath, label = "views", ...flags] = process.argv.slice(2);
if (!imagePath) {
  console.error("用法: node scripts/experiment-views.mjs <imagePath> <label> [--restyle]");
  process.exit(2);
}
const restyle = flags.includes("--restyle");
const RESTYLE_PROMPT =
  "把图中的角色变成一个立体的圆滚滚毛绒公仔，身体饱满有厚度，保持原有的造型、颜色和表情，完整展示全身，正面略微俯视，纯白背景，单个主体居中";

loadEnvFile();
const lux = createLux3dClient(loadConfig().lux3d);
const outPath = (name) => fileURLToPath(new URL(`../data-smoke/${name}`, import.meta.url));
await fs.mkdir(fileURLToPath(new URL("../data-smoke/", import.meta.url)), { recursive: true });

const started = Date.now();
const log = (message) => console.log(`[${((Date.now() - started) / 1000).toFixed(0)}s] ${message}`);

async function wait(taskId) {
  for (;;) {
    await new Promise((resolve) => setTimeout(resolve, 3000));
    const detail = await lux.getTask(taskId);
    if (detail?.status === 3) return detail.outputs;
    if (detail?.status === 4 || detail?.status === 6) throw new Error(`task ${taskId} failed (status ${detail.status})`);
  }
}

const source = await normalizeImage(await fs.readFile(imagePath));
let imageUrl = await lux.uploadImage({ buffer: source.buffer, filename: "source.jpg", contentType: source.contentType });
log("原图已上传");

if (restyle) {
  imageUrl = pickImageUrl(await wait(await lux.createMultimodalToImage(imageUrl, RESTYLE_PROMPT))) ?? imageUrl;
  log("毛绒改写完成");
}

const views = pickFourViewUrls(await wait(await lux.createFourView(imageUrl)));
if (!views) throw new Error("四视图任务没有返回 4 个图片地址");
await fs.writeFile(outPath(`${label}-fourview.json`), JSON.stringify(views, null, 2));
log(`四视图完成：${views.length} 张 → data-smoke/${label}-fourview.json`);

const glb = await lux.downloadModel(pickGlbUrl(await wait(await lux.createImageTo3dFromViews(views))));
await fs.writeFile(outPath(`${label}-views.glb`), glb);
log(`四视图建模完成 → data-smoke/${label}-views.glb ${JSON.stringify(inspectGlb(glb))}`);
