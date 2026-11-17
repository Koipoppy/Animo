import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import sharp from "sharp";
import { createApp } from "../src/app.mjs";
import { loadConfig } from "../src/config.mjs";
import { createStore } from "../src/store.mjs";

export async function makeJpeg(width = 400, height = 300, background = "#cc5522") {
  return sharp({ create: { width, height, channels: 3, background } }).jpeg().toBuffer();
}

export async function makeTransparentPng() {
  return sharp({ create: { width: 300, height: 300, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 0 } } })
    .png()
    .toBuffer();
}

// A structurally valid GLB: one triangle mesh, one material, no binary chunk needed for inspection.
// `extent` is the POSITION bounding box size [x, y, z], used to exercise the flat-model detector.
export function makeGlb(extent = [0.2, 0.2, 0.1]) {
  const gltf = {
    asset: { version: "2.0", generator: "test" },
    accessors: [
      { componentType: 5126, count: 3, type: "VEC3", min: extent.map((v) => -v / 2), max: extent.map((v) => v / 2) },
      { componentType: 5123, count: 3, type: "SCALAR" },
    ],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 }, indices: 1 }] }],
    materials: [{}],
  };
  let json = Buffer.from(JSON.stringify(gltf));
  json = Buffer.concat([json, Buffer.alloc((4 - (json.length % 4)) % 4, 0x20)]);
  const header = Buffer.alloc(12);
  header.writeUInt32LE(0x46546c67, 0);
  header.writeUInt32LE(2, 4);
  header.writeUInt32LE(12 + 8 + json.length, 8);
  const chunk = Buffer.alloc(8);
  chunk.writeUInt32LE(json.length, 0);
  chunk.writeUInt32LE(0x4e4f534a, 4);
  return Buffer.concat([header, chunk, json]);
}

// A rigged+animated GLB: one skin with one joint and named clips. Enough for
// the backend's `skins > 0 && animations > 0` acceptance check.
export function makeAnimatedGlb(animationNames = ["Idle_Loop", "Walk_Loop"]) {
  const gltf = {
    asset: { version: "2.0", generator: "test" },
    accessors: [
      { componentType: 5126, count: 3, type: "VEC3", min: [-0.1, -0.1, -0.1], max: [0.1, 0.1, 0.1] },
      { componentType: 5123, count: 3, type: "SCALAR" },
    ],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 }, indices: 1 }] }],
    materials: [{}],
    skins: [{ joints: [0] }],
    nodes: [{ name: "root" }],
    animations: animationNames.map((name) => ({ name, channels: [], samplers: [] })),
  };
  let json = Buffer.from(JSON.stringify(gltf));
  json = Buffer.concat([json, Buffer.alloc((4 - (json.length % 4)) % 4, 0x20)]);
  const header = Buffer.alloc(12);
  header.writeUInt32LE(0x46546c67, 0);
  header.writeUInt32LE(2, 4);
  header.writeUInt32LE(12 + 8 + json.length, 8);
  const chunk = Buffer.alloc(8);
  chunk.writeUInt32LE(json.length, 0);
  chunk.writeUInt32LE(0x4e4f534a, 4);
  return Buffer.concat([header, chunk, json]);
}

export function fakeLux3d(overrides = {}) {
  const calls = { upload: [], image: [], text: [], restyle: [], fourView: [], multiImage: [], rig: [], retarget: [], download: [], task: [] };
  let retargetIds = [];
  const client = {
    calls,
    async uploadImage(args) {
      calls.upload.push(args);
      return "https://cdn.example/uploaded.jpg";
    },
    async createImageTo3d(url) {
      calls.image.push(url);
      return 111;
    },
    async createTextTo3d(prompt) {
      calls.text.push(prompt);
      return 222;
    },
    async createMultimodalToImage(url, prompt) {
      calls.restyle.push({ url, prompt });
      return 333;
    },
    async createFourView(url) {
      calls.fourView.push(url);
      return 444;
    },
    async createImageTo3dFromViews(urls) {
      calls.multiImage.push(urls);
      return 555;
    },
    async createRig(modelUrl) {
      calls.rig.push(modelUrl);
      return 666;
    },
    async createRetarget(rigModelUrl, animationIds) {
      calls.retarget.push({ rigModelUrl, animationIds });
      retargetIds = animationIds;
      return 777;
    },
    async getTask(id) {
      calls.task.push(id);
      // 333 is the restyle task, which returns a picture; 444 returns the four views.
      if (id === 333) return { status: 3, outputs: [{ content: "https://cdn.example/plush.jpg" }] };
      if (id === 444) {
        return {
          status: 3,
          outputs: [{ content: JSON.stringify(["https://cdn.example/v1.png", "https://cdn.example/v2.png", "https://cdn.example/v3.png", "https://cdn.example/v4.png"]) }],
        };
      }
      if (id === 666) return { status: 3, outputs: [{ content: "https://cdn.example/rigged.glb" }] };
      if (id === 777) {
        // Lux3D names combined clips animation_1..N, so the backend must rename them.
        const names = retargetIds.map((_, index) => `animation_${index + 1}`);
        return {
          status: 3,
          outputs: [{
            content: JSON.stringify({
              actions: retargetIds.map((animation_id, index) => ({
                animation_id,
                status: "success",
                animationGlbUrl: "https://cdn.example/animated.glb",
                animation_name: names[index],
              })),
            }),
          }],
        };
      }
      return { status: 3, outputs: [{ content: "https://cdn.example/model.glb" }] };
    },
    async downloadModel(url) {
      calls.download.push(url);
      if (url === "https://cdn.example/animated.glb") return makeAnimatedGlb(["animation_1", "animation_2", "animation_3", "animation_4", "animation_5"]);
      return makeGlb();
    },
    ...overrides,
  };
  return client;
}

export async function makeServer({ env = {}, lux3d, fetchImage } = {}) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "animo-test-"));
  const config = loadConfig({ DATA_DIR: dataDir, LUX3D_API_KEY: lux3d ? "test-key" : "", ...env });
  config.dataDir = dataDir;
  const store = createStore(dataDir);
  await store.init();
  const silent = { error() {}, warn() {}, log() {} };
  const app = createApp({ config, store, lux3d, log: silent, sleepImpl: async () => {}, fetchImage });
  const server = await new Promise((resolve) => {
    const instance = app.listen(0, "127.0.0.1", () => resolve(instance));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    base,
    app,
    config,
    async close() {
      // Let background jobs finish writing before the directory disappears.
      await app.locals.jobs.idle();
      await new Promise((resolve) => server.close(resolve));
      await fs.rm(dataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    },
  };
}

export async function waitForJob(base, id, { headers = {}, timeoutMs = 5000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await fetch(`${base}/api/v1/pets/${id}`, { headers });
    const job = await response.json();
    if (job.status === "succeeded" || job.status === "failed") return job;
    if (Date.now() > deadline) throw new Error(`job ${id} did not finish: ${JSON.stringify(job)}`);
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

export function photoForm(buffer, extra = {}) {
  const form = new FormData();
  form.append("image", new Blob([buffer], { type: "image/jpeg" }), "x.jpg");
  for (const [key, value] of Object.entries(extra)) form.append(key, value);
  return form;
}
