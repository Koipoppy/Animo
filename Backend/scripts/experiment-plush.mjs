// Throwaway experiment: does restyling a flat mascot into a plush render give image-to-3D real volume?
//   node scripts/experiment-plush.mjs <imageUrl> <label>
// Spends credits. Writes data-smoke/<label>-step1.jpg (restyled image) and <label>-plush.glb.
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { loadConfig, loadEnvFile } from "../src/config.mjs";
import { inspectGlb } from "../src/glb.mjs";
import { normalizeImage } from "../src/image.mjs";
import { createLux3dClient, pickGlbUrl, pickImageUrl } from "../src/lux3d.mjs";
import { fetchRemoteImage } from "../src/remote-image.mjs";

const [url, label = "exp"] = process.argv.slice(2);
if (!url) {
  console.error("用法: node scripts/experiment-plush.mjs <imageUrl> <label>");
  process.exit(2);
}
const PROMPT = "把图中的角色变成一个立体的圆滚滚毛绒公仔，身体饱满有厚度，保持原有的造型、颜色和表情，正面略微俯视，纯白背景，单个主体居中";

loadEnvFile();
const config = loadConfig();
const lux = createLux3dClient(config.lux3d);
const out = (name) => fileURLToPath(new URL(`../data-smoke/${name}`, import.meta.url));
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

const original = await normalizeImage(await fetchRemoteImage(url));
const originalUrl = await lux.uploadImage({ buffer: original.buffer, filename: "in.jpg", contentType: original.contentType });
log("原图已上传");

const imageTask = await lux.createMultimodalToImage(originalUrl, PROMPT);
const imageOutputs = await wait(imageTask);
log(`改写完成: ${JSON.stringify(imageOutputs)}`);
const restyledUrl = pickImageUrl(imageOutputs);
if (!restyledUrl) throw new Error("没有拿到改写后的图片");
await fs.writeFile(out(`${label}-step1.jpg`), await lux.downloadModel(restyledUrl));
log(`改写后的图片 → data-smoke/${label}-step1.jpg`);

const modelTask = await lux.createImageTo3d(restyledUrl);
const modelOutputs = await wait(modelTask);
const glb = await lux.downloadModel(pickGlbUrl(modelOutputs));
await fs.writeFile(out(`${label}-plush.glb`), glb);
log(`3D 模型完成 → data-smoke/${label}-plush.glb  ${JSON.stringify(inspectGlb(glb))}`);
