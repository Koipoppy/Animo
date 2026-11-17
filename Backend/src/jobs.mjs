import crypto from "node:crypto";
import { inspectGlb, isGlb, renameGlbAnimations } from "./glb.mjs";
import { rigGlb } from "./rig.mjs";
import { normalizeImage } from "./image.mjs";
import { parseRetargetActions, pickFourViewUrls, pickGlbUrl, pickImageUrl, pickRigUrl } from "./lux3d.mjs";
import { fetchRemoteImage } from "./remote-image.mjs";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const ACTIVE_STATUSES = new Set(["queued", "fetching", "uploading", "styling", "views", "generating", "downloading"]);
const STAGE_PROGRESS = { queued: 0, fetching: 0.03, uploading: 0.06, styling: 0.1, views: 0.2, generating: 0.36, downloading: 0.9, succeeded: 1, failed: 0 };
const MAX_JOB_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const POLL_INTERVAL_MS = 3000;
// Lux3D rejects new tasks while another is running on the account.
const RETRY_DELAYS_MS = [5000, 15000, 45000];

// Requested in one retarget task; the combined GLB is played by clip name on the client.
export const ANIMATION_PRESETS = ["Idle_Loop", "Walk_Loop", "Punch_Jab", "Hit_Chest", "Death01"];

export const INPUT_KINDS = ["photo", "url", "text"];

// "faithful": send the image straight to image-to-3D. Right for photos of real objects.
// "plush":    first restyle into a chubby plush-toy render. Right for flat art (stickers, logos, mascots),
//             which otherwise comes back as a paper-thin cut-out (measured: 2 mm deep vs 12 cm).
export const STYLES = ["plush", "faithful"];

export function defaultStyle(kind) {
  if (kind === "text") return null;
  return kind === "url" ? "plush" : "faithful";
}

// The A-pose sentence is not cosmetic: Lux3D's humanoid rigger rejects models
// whose arms are bent or held against the body (MODEL_NOT_RIGGABLE). Asking for
// it during restyle/four-view gives the cloud rig a real chance to succeed.
const PLUSH_PROMPT =
  "把图中的角色变成一个立体的圆滚滚毛绒公仔，身体饱满有厚度，保持原有的造型、颜色和表情，完整展示全身，正面略微俯视，纯白背景，单个主体居中；人形角色请让双臂向两侧自然张开成 A-Pose、手肘伸直，双腿分开站稳";

const FOUR_VIEW_PROMPT =
  "保持同一角色、同一套配色和同一姿势；人形角色保持 A-Pose：双臂向两侧张开、手肘伸直、双腿分开站稳，不要抱臂或屈肘";

// Public view: never exposes the source image, its URL, internal paths or Lux3D internals.
export function toPublicJob(job) {
  return {
    id: job.id,
    name: job.name,
    inputKind: job.inputKind,
    style: job.style,
    views: Boolean(job.views),
    status: job.status,
    stage: job.stage,
    progress: job.progress,
    source: job.source,
    demo: job.demo,
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
    // Demo jobs have no stored model, so advertising a URL would send clients to a 404.
    ...(job.status === "succeeded" && !job.demo ? { modelUrl: `/api/v1/models/${job.id}.glb`, stats: job.stats } : {}),
    ...(job.status === "failed" ? { error: job.error } : {}),
  };
}

function userFacing(message) {
  return Object.assign(new Error(message), { userFacing: true });
}

export function createJobManager({
  config,
  store,
  lux3d,
  log = console,
  sleepImpl = sleep,
  fetchImage = fetchRemoteImage,
}) {
  const jobs = new Map();
  const pending = [];
  const ipCounts = new Map();
  const saves = new Set();
  let running = 0;

  // Fire-and-forget persistence, but tracked so shutdown can wait for it.
  function persist(job) {
    const save = store
      .saveJob(job)
      .catch((error) => log.error("保存任务失败", error.message))
      .finally(() => saves.delete(save));
    saves.add(save);
  }

  function touch(job, patch) {
    Object.assign(job, patch, { updatedAt: new Date().toISOString() });
    if (patch.progress === undefined && job.status in STAGE_PROGRESS) job.progress = STAGE_PROGRESS[job.status];
    persist(job);
  }

  // Waits for a Lux3D task. `report` maps the poll count to a progress value, or is omitted for quiet stages.
  async function pollTask(job, taskId, { report, failure = "Lux3D 生成失败，换一张主体更清晰、背景更简单的图片试试", timeoutMs = config.lux3d.taskTimeoutMs } = {}) {
    const deadline = Date.now() + timeoutMs;
    let polls = 0;
    while (Date.now() < deadline) {
      await sleepImpl(POLL_INTERVAL_MS);
      polls += 1;
      const detail = await lux3d.getTask(taskId);
      // status: 0 init, 1 running, 3 succeeded, 4 failed, 6 cancelled
      if (detail?.status === 4 || detail?.status === 6) throw userFacing(detail.status === 6 ? "生成任务被取消" : failure);
      if (detail?.status === 3) return detail.outputs;
      if (report) touch(job, { progress: report(polls) });
    }
    throw userFacing("生成时间过长，已停止等待，请稍后重试");
  }

  // Cloud rigging still has to run after generation, so the generation bar stops
  // at 0.62 instead of claiming 90%. RIG_DISABLED=1 keeps the old single-stage bar.
  const cloudRigEnabled = process.env.RIG_DISABLED !== "1";
  const generationCap = cloudRigEnabled ? 0.62 : 0.9;
  // Asymptotic progress: the real duration is unknown, so never claim completion early.
  const modelProgress = (polls) => Math.min(generationCap, 0.2 + (generationCap - 0.2) * (1 - Math.exp(-polls / 40)));
  const stylingProgress = (polls) => Math.min(0.19, 0.1 + 0.09 * (1 - Math.exp(-polls / 8)));
  const viewProgress = (polls) => Math.min(0.34, 0.19 + 0.15 * (1 - Math.exp(-polls / 10)));
  // After the four views the 3D step starts at a higher baseline, so it must not jump backwards.
  const fourViewModelProgress = (polls) => Math.min(generationCap, 0.36 + (generationCap - 0.36) * (1 - Math.exp(-polls / 30)));
  const rigProgress = (polls) => Math.min(0.76, 0.64 + 0.12 * (1 - Math.exp(-polls / 20)));
  const retargetProgress = (polls) => Math.min(0.9, 0.78 + 0.12 * (1 - Math.exp(-polls / 20)));

  async function withBusyRetry(job, create) {
    for (let attempt = 0; ; attempt += 1) {
      try {
        return await create();
      } catch (error) {
        const delay = RETRY_DELAYS_MS[attempt];
        if (!error.retryable || delay === undefined) throw error;
        touch(job, { stage: "排队等待生成通道" });
        await sleepImpl(delay);
      }
    }
  }

  async function runDemo(job) {
    touch(job, { status: "generating", stage: "示例模式：正在准备内置宠物" });
    await sleepImpl(1200);
    touch(job, { status: "succeeded", stage: "示例宠物已就绪", source: "demo" });
  }

  // Turns whatever the user gave us into a Lux3D task id. `image` is the
  // normalised reference picture used for upload.
  async function startTask(job, input, image) {
    if (input.kind === "text") {
      touch(job, { status: "generating", stage: "Lux3D 正在根据描述生成 3D 模型", progress: 0.2 });
      return withBusyRetry(job, () => lux3d.createTextTo3d(input.prompt));
    }

    touch(job, { status: "uploading", stage: "正在上传图片" });
    let imageUrl = await lux3d.uploadImage({
      buffer: image.buffer,
      filename: `animo-${job.id.slice(0, 8)}.${image.ext}`,
      contentType: image.contentType,
    });

    if (job.style === "plush") imageUrl = await restyleAsPlush(job, imageUrl);

    // Character art only shows one side, so generate the other three views and model from all four.
    // Measured on a Q-version character: this keeps the face intact and gives a real back and side.
    if (job.style === "plush") {
      const views = await fourViews(job, imageUrl);
      if (views) {
        job.views = true;
        touch(job, { status: "generating", stage: "Lux3D 正在按四个视角生成 3D 模型", progress: 0.36 });
        return withBusyRetry(job, () => lux3d.createImageTo3dFromViews(views));
      }
    }

    job.views = false;
    touch(job, { status: "generating", stage: "Lux3D 正在生成 3D 模型", progress: 0.2 });
    return withBusyRetry(job, () => lux3d.createImageTo3d(imageUrl));
  }

  // Returns four view URLs, or null when the enhancement is unavailable (the caller then models from
  // the single image, which still works).
  async function fourViews(job, imageUrl) {
    try {
      touch(job, { status: "views", stage: "正在补全侧面和背面视角" });
      const taskId = await withBusyRetry(job, () => lux3d.createFourView(imageUrl, FOUR_VIEW_PROMPT));
      const urls = pickFourViewUrls(await pollTask(job, taskId, { report: viewProgress, failure: "四视图生成失败" }));
      if (!urls) throw new Error("四视图任务没有返回 4 个地址");
      return urls;
    } catch (error) {
      log.warn?.(`任务 ${job.id} 四视图失败，改用单图建模: ${error.message}`);
      job.views = false;
      return null;
    }
  }

  // Flat art becomes a paper-thin cut-out in 3D, so first turn it into a chubby plush render.
  // The restyle is an enhancement: if it fails, fall back to the original image instead of failing the pet.
  async function restyleAsPlush(job, imageUrl) {
    try {
      touch(job, { status: "styling", stage: "正在把它变成圆滚滚的毛绒造型" });
      const taskId = await withBusyRetry(job, () => lux3d.createMultimodalToImage(imageUrl, PLUSH_PROMPT));
      const outputs = await pollTask(job, taskId, { report: stylingProgress, failure: "造型改写失败" });
      return pickImageUrl(outputs) ?? imageUrl;
    } catch (error) {
      log.warn?.(`任务 ${job.id} 毛绒改写失败，改用原图: ${error.message}`);
      job.style = "faithful";
      return imageUrl;
    }
  }

  // Cloud humanoid rig + preset retargeting, straight through Lux3D. Both steps
  // read the previous step's public CDN URL, so nothing is uploaded from here.
  // Returns null on any failure so the job falls back to the local Blender rig.
  async function runCloudRig(job, meshUrl) {
    try {
      touch(job, { status: "downloading", stage: "Lux3D 正在绑定骨骼", progress: 0.64 });
      const rigTaskId = await withBusyRetry(job, () => lux3d.createRig(meshUrl));
      job.lux3dRigTaskId = rigTaskId;
      const rigUrl = pickRigUrl(await pollTask(job, rigTaskId, {
        report: rigProgress,
        failure: "骨骼绑定失败",
        timeoutMs: config.lux3d.rigTimeoutMs,
      }));
      if (!rigUrl) throw new Error("绑骨任务没有返回模型地址");

      touch(job, { stage: "Lux3D 正在套用动作", progress: 0.78 });
      const retargetTaskId = await withBusyRetry(job, () => lux3d.createRetarget(rigUrl, ANIMATION_PRESETS));
      job.lux3dRetargetTaskId = retargetTaskId;
      const actions = parseRetargetActions(await pollTask(job, retargetTaskId, {
        report: retargetProgress,
        failure: "动作套用失败",
        timeoutMs: config.lux3d.rigTimeoutMs,
      }));
      const successful = (actions ?? []).filter((action) => action.ok);
      if (successful.length === 0) throw new Error("动作任务没有产出可用片段");

      const clipUrl = successful[0].url;
      const names = successful.map((action) => action.animationId).filter(Boolean);
      // Lux3D's combined GLB names clips animation_1..N; restore the preset ids
      // so the client can play Idle_Loop / Walk_Loop by name.
      const glb = renameGlbAnimations(await lux3d.downloadModel(clipUrl), names);
      if (!isGlb(glb)) throw new Error("动作模型不是有效 GLB");
      const stats = inspectGlb(glb);
      if (stats.skins < 1 || stats.animations < 1) {
        throw new Error(`动作模型没有骨骼动画（skins=${stats.skins}, animations=${stats.animations}）`);
      }
      return { glb, stats };
    } catch (error) {
      job.cloudRigError = error.message;
      log.warn?.(`任务 ${job.id} 云端绑骨跳过，回退本地 Blender: ${error.message}`);
      return null;
    }
  }

  async function runLux3d(job, input) {
    // One normalised reference image feeds the Lux3D upload; text needs none.
    let reference = null;
    if (input.kind === "url") {
      touch(job, { status: "fetching", stage: "正在获取网络图片" });
      reference = await normalizeImage(await fetchImage(input.url));
    } else if (input.kind === "photo") {
      reference = await normalizeImage(input.buffer);
    }

    const taskId = await startTask(job, input, reference);
    job.lux3dTaskId = taskId;
    const outputs = await pollTask(job, taskId, { report: job.views ? fourViewModelProgress : modelProgress });

    const modelUrl = pickGlbUrl(outputs);
    if (!modelUrl) throw userFacing("Lux3D 已完成，但没有返回 GLB 模型");

    let finalGlb = null;
    let stats = null;

    // Preferred: Lux3D cloud humanoid rig + Idle/Walk/battle preset retargeting.
    if (cloudRigEnabled) {
      const animated = await runCloudRig(job, modelUrl);
      if (animated) {
        finalGlb = animated.glb;
        stats = { ...animated.stats, rigged: true, engine: "lux3d" };
      }
    }

    // Fallback: the original model + local Blender template rig.
    if (!finalGlb) {
      touch(job, {
        status: "downloading",
        stage: job.cloudRigError ? "云端绑骨未通过，改用本地 Blender" : "正在保存模型",
      });
      const glb = await lux3d.downloadModel(modelUrl);
      if (!isGlb(glb)) throw userFacing("下载到的文件不是有效的 GLB 模型");
      stats = inspectGlb(glb);
      if (job.cloudRigError) stats.cloudRigSkipped = job.cloudRigError.slice(0, 160);

      // Best-effort skeleton + idle animation; a failure here must not fail the job.
      finalGlb = glb;
      if (process.env.RIG_DISABLED !== "1") {
        touch(job, { status: "downloading", stage: "正在绑定骨骼" });
        const rigged = await rigGlb(glb, process.env.RIG_MODE || "idle");
        if (rigged && isGlb(rigged)) {
          // Report the delivered asset, not the pre-rig mesh: the app shows
          // "本地骨骼 · N 段动作" from these numbers.
          try {
            const riggedStats = inspectGlb(rigged);
            if (riggedStats.skins > 0) {
              finalGlb = rigged;
              stats = { ...stats, ...riggedStats, rigged: true, engine: "local" };
            }
          } catch (error) {
            log.warn?.(`任务 ${job.id} 绑骨产物无法解析，保留静态模型: ${error.message}`);
          }
        }
      }
    }

    await store.saveModel(job.id, finalGlb);
    touch(job, { status: "succeeded", stage: "宠物已孵化", source: "lux3d", stats });
  }

  async function execute(job, input) {
    try {
      if (config.demoMode) await runDemo(job);
      else await runLux3d(job, input);
    } catch (error) {
      log.error(`任务 ${job.id} 失败:`, error.message);
      const known = error.userFacing || error.name === "Lux3dError";
      touch(job, { status: "failed", stage: "失败", error: known ? error.message : "生成失败，请稍后重试" });
    }
  }

  function pump() {
    while (running < config.limits.concurrency && pending.length > 0) {
      const { job, input } = pending.shift();
      running += 1;
      execute(job, input).finally(() => {
        running -= 1;
        pump();
      });
    }
  }

  return {
    // `input` is {kind:"photo", buffer} | {kind:"url", url} | {kind:"text", prompt}.
    // Returns {job} on success or {error: "busy" | "rate-limited"}.
    submit({ name, input, style, ip }) {
      const day = new Date().toISOString().slice(0, 10);
      const ipKey = `${day}:${ip}`;
      if ((ipCounts.get(ipKey) ?? 0) >= config.limits.perIpPerDay) return { error: "rate-limited" };
      if (pending.length >= config.limits.maxQueued) return { error: "busy" };

      ipCounts.set(ipKey, (ipCounts.get(ipKey) ?? 0) + 1);
      const now = new Date().toISOString();
      const job = {
        id: crypto.randomUUID(),
        name,
        inputKind: input.kind,
        style: STYLES.includes(style) ? style : defaultStyle(input.kind),
        status: "queued",
        stage: "排队中",
        progress: 0,
        demo: config.demoMode,
        source: config.demoMode ? "demo" : "lux3d",
        createdAt: now,
        updatedAt: now,
      };
      jobs.set(job.id, job);
      persist(job);
      pending.push({ job, input });
      pump();
      return { job };
    },

    get: (id) => jobs.get(id),

    // On restart, anything that was mid-flight cannot be resumed because its input lived only in memory.
    async restore() {
      for (const job of await store.loadJobs()) {
        const age = Date.now() - new Date(job.createdAt).getTime();
        if (age > MAX_JOB_AGE_MS) continue;
        if (ACTIVE_STATUSES.has(job.status)) {
          Object.assign(job, { status: "failed", stage: "失败", error: "服务重启，任务已中断，请重新创建" });
          await store.saveJob(job);
        }
        jobs.set(job.id, job);
      }
    },

    // Resolves once nothing is queued or running and every job record has been written.
    async idle() {
      while (running > 0 || pending.length > 0 || saves.size > 0) {
        await Promise.allSettled([...saves]);
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
    },

    stats: () => ({ running, queued: pending.length, tracked: jobs.size }),
  };
}
