import crypto from "node:crypto";

export class Lux3dError extends Error {
  constructor(message, { code = "LUX3D_ERROR", status = 0, retryable = false } = {}) {
    super(message);
    this.name = "Lux3dError";
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const MAX_MODEL_BYTES = 100 * 1024 * 1024;
const CONCURRENCY_LIMIT = "GENERATION_CONCURRENCY_LIMIT_EXCEEDED";

// errorCode values come from the OUS upload docs (OusV2BusinessErrorCode).
const UPLOAD_MESSAGES = {
  2: "图片文件大小不合适，请换一张再试",
  3: "图片格式不支持，请使用 JPG 或 PNG",
  4: "图片内容无法识别，请换一张再试",
  5: "这张图片没有通过内容安全检测，请换一张再试",
};
const UPLOAD_BUSY_CODES = new Set([6, 9, 10]);

// An image task returns a single result image URL in outputs[0].content.
export function pickImageUrl(outputs) {
  const url = (outputs ?? []).map((item) => item?.content).find((value) => typeof value === "string" && /^https?:\/\//i.test(value));
  return url ?? null;
}

// The four-view task returns a JSON array string, ordered front-3/4, opposite-3/4, side, back.
export function pickFourViewUrls(outputs) {
  const raw = (outputs ?? []).map((item) => item?.content).find((value) => typeof value === "string" && value.trim().startsWith("["));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    const urls = parsed.filter((value) => typeof value === "string" && /^https?:\/\//i.test(value));
    return urls.length === 4 ? urls : null;
  } catch {
    return null;
  }
}

// G1 returns [zip, glb]; G1-Turbo returns only the requested formats.
export function pickGlbUrl(outputs) {
  const urls = (outputs ?? [])
    .map((item) => item?.content)
    .filter((value) => typeof value === "string" && /^https?:\/\//i.test(value));
  return urls.find((url) => /\.glb(\?|$)/i.test(url)) ?? (urls.length === 1 ? urls[0] : null);
}

// Rigging returns the rigged GLB URL in the same shape as generation.
export const pickRigUrl = pickGlbUrl;

const ACTION_SUCCESS = new Set(["success", "succeeded", "completed", "done"]);

// Retarget returns outputs[0].content as a JSON string with one entry per action.
// In `combined` mode every successful action points at the same GLB.
export function parseRetargetActions(outputs) {
  const raw = (outputs ?? [])
    .map((item) => item?.content)
    .find((value) => typeof value === "string" && value.trim().startsWith("{"));
  if (!raw) return null;
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!Array.isArray(payload?.actions)) return null;
  return payload.actions.map((action) => {
    const animationId = action?.animation_id ?? action?.animationId ?? action?.id ?? null;
    const url = action?.animationGlbUrl ?? action?.animation_glb_url ?? action?.glbUrl ?? action?.glb_url ?? action?.url ?? action?.fileUrl ?? action?.file_url ?? null;
    const ok = ACTION_SUCCESS.has(String(action?.status ?? "").trim().toLowerCase()) && typeof url === "string" && /^https?:\/\//i.test(url);
    return {
      animationId: animationId == null ? null : String(animationId),
      status: String(action?.status ?? ""),
      url: typeof url === "string" ? url : null,
      ok,
    };
  });
}

export function createLux3dClient({
  baseUrl,
  apiKey,
  version = "G1-Turbo",
  faceCount = 30000,
  fetchImpl = globalThis.fetch,
  sleepImpl = sleep,
}) {
  if (!apiKey) throw new Error("Lux3D API key is required");

  async function send(url, init, timeoutMs) {
    try {
      return await fetchImpl(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
    } catch {
      throw new Lux3dError("连接 Lux3D 服务失败，请检查网络后重试", { code: "NETWORK", retryable: true });
    }
  }

  async function readJson(response) {
    const text = await response.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch {
      throw new Lux3dError(`Lux3D 返回了无法识别的内容 (HTTP ${response.status})`, {
        code: "BAD_RESPONSE",
        status: response.status,
        retryable: response.status >= 500,
      });
    }
  }

  // Task APIs reply {c, m, d}; gateway errors reply {code, message, status}.
  function failure(payload, status) {
    if (status === 401 || status === 403) {
      return new Lux3dError("Lux3D 密钥无效或没有权限", { code: "LUX3D_AUTH", status });
    }
    const code = String(payload?.c ?? payload?.code ?? status);
    const message = String(payload?.m || payload?.message || `Lux3D 请求失败 (HTTP ${status})`);
    return new Lux3dError(message, {
      code,
      status,
      retryable: status === 429 || status >= 500 || code === CONCURRENCY_LIMIT,
    });
  }

  // The API key goes in the raw Authorization header; the docs say no "Bearer" prefix.
  async function api(path, { method = "GET", body } = {}) {
    const response = await send(
      `${baseUrl}${path}`,
      {
        method,
        headers: { Authorization: apiKey, ...(body ? { "Content-Type": "application/json" } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      },
      30_000,
    );
    const payload = await readJson(response);
    if (!response.ok || (payload.c !== undefined && String(payload.c) !== "0")) {
      throw failure(payload, response.status);
    }
    return payload;
  }

  async function ous(globalDomain, token, path, init = {}) {
    const response = await send(`${globalDomain}${path}`, { ...init, headers: { "ous-token-v2": token } }, 60_000);
    const payload = await readJson(response);
    if (!response.ok || String(payload.c) !== "0") throw failure(payload, response.status);
    return payload.d;
  }

  function uploadFailure(detail) {
    const code = detail.errorCode;
    return new Lux3dError(
      UPLOAD_MESSAGES[code] ?? (UPLOAD_BUSY_CODES.has(code) ? "上传服务繁忙，请稍后再试" : "图片上传失败，请重试"),
      { code: `UPLOAD_${code ?? detail.status}`, retryable: UPLOAD_BUSY_CODES.has(code) },
    );
  }

  // Status polling is bound to the upload token: no task id is sent.
  async function waitForUpload(globalDomain, token) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const detail = await ous(globalDomain, token, "/ous/api/v2/upload/status");
      if (detail?.status === 5) {
        if (!detail.url) throw new Lux3dError("图片上传完成，但没有拿到图片地址", { code: "UPLOAD_NO_URL" });
        return detail.url;
      }
      if (detail?.status === 6 || detail?.status === 8) throw uploadFailure(detail);
      await sleepImpl(300);
    }
    throw new Lux3dError("图片上传超时，请重试", { code: "UPLOAD_TIMEOUT", retryable: true });
  }

  // Uploads a photo to Aholo storage and returns the URL the generation API can read.
  async function uploadImage({ buffer, filename, contentType }) {
    const grant = await api("/asset/v1/token");
    const { ousToken, globalDomain, blockSize } = grant.d ?? grant;
    if (!ousToken || !/^https:\/\//i.test(globalDomain ?? "") || !(blockSize > 0)) {
      throw new Lux3dError("Lux3D 没有返回有效的上传凭证", { code: "UPLOAD_TOKEN_INVALID" });
    }
    const md5 = crypto.createHash("md5").update(buffer).digest("hex");

    if (buffer.length <= blockSize) {
      const form = new FormData();
      form.append("md5", md5);
      form.append("file", new Blob([buffer], { type: contentType }), filename);
      await ous(globalDomain, ousToken, "/ous/api/v2/single/upload", { method: "POST", body: form });
    } else {
      const blocks = Math.ceil(buffer.length / blockSize);
      const query = new URLSearchParams({ md5, blocks: String(blocks), size: String(buffer.length), name: filename });
      const init = await ous(globalDomain, ousToken, `/ous/api/v2/block/upload/init?${query}`, { method: "POST" });
      if (!init?.deduplicated) {
        // Sequential on purpose: the docs advise at most two concurrent parts per file.
        for (let index = 0; index < blocks; index += 1) {
          const form = new FormData();
          form.append("block", String(index + 1));
          form.append("file", new Blob([buffer.subarray(index * blockSize, (index + 1) * blockSize)]), filename);
          await ous(globalDomain, ousToken, "/ous/api/v2/block/upload/part", { method: "POST", body: form });
        }
      }
    }
    return waitForUpload(globalDomain, ousToken);
  }

  async function createImageTo3d(imageUrl) {
    const payload = await api("/lux3d/v1/generate/img-to-3d/task/create", {
      method: "POST",
      body: { img: imageUrl, version, faceCount, outputFormat: ["glb"], enablePbr: true },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // Image-to-image restyle. Used to turn flat 2D art (stickers, logos, mascots) into a chubby, shaded,
  // plush-toy render, because image-to-3D on flat art tends to return a paper-thin cut-out.
  async function createMultimodalToImage(imageUrl, prompt) {
    const payload = await api("/lux3d/v1/generate/multimodal-to-image/task/create", {
      method: "POST",
      body: { img: imageUrl, prompt },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // Generates FRONT_THREE_QUARTER / OPPOSITE_THREE_QUARTER / SIDE / BACK views from one picture.
  // Single-view art has no information about the back, so this is what makes the far side plausible.
  async function createFourView(imageUrl, prompt) {
    const payload = await api("/lux3d/v1/generate/image-to-four-view/task/create", {
      method: "POST",
      body: { img: imageUrl, ...(prompt ? { prompt } : {}) },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // Multi-image image-to-3D: the first picture is the main reference, the rest refine the shape.
  async function createImageTo3dFromViews(imageUrls) {
    const payload = await api("/lux3d/v1/generate/img-to-3d/task/create", {
      method: "POST",
      body: { imgs: imageUrls, version, faceCount, outputFormat: ["glb"], enablePbr: true },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // Text-only creation. A cartoon style suits "cute pet" prompts better than the photoreal default.
  async function createTextTo3d(prompt, { style = "cartoon" } = {}) {
    const payload = await api("/lux3d/v1/generate/text-to-3d/task/create", {
      method: "POST",
      body: { prompt, style, version, faceCount, outputFormat: ["glb"], enablePbr: true },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  async function getTask(taskId) {
    const payload = await api(`/lux3d/v1/generate/task/get?taskid=${encodeURIComponent(taskId)}`);
    return payload.d;
  }

  // Humanoid auto-rig. The input must be a public GLB URL; Lux3D's CDN link
  // qualifies, so the generated model does not need a round trip through us.
  async function createRig(modelUrl) {
    const payload = await api("/lux3d/v1/animations/rig/task/create", {
      method: "POST",
      body: { modelUrl },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回绑骨任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // Preset retargeting. `combined` packages every requested clip into one GLB,
  // which is what the Android client loads and plays by clip name.
  async function createRetarget(rigModelUrl, animationIds) {
    const payload = await api("/lux3d/v1/animations/retarget/task/create", {
      method: "POST",
      body: {
        rigModelUrl,
        animationIds,
        outFormat: "glb",
        animationOutputMode: "combined",
        animateInPlace: true,
      },
    });
    if (!payload.d) throw new Lux3dError("Lux3D 没有返回动作任务编号", { code: "NO_TASK_ID" });
    return payload.d;
  }

  // The result URL is a CDN link, so the API key is deliberately not sent with it.
  async function downloadModel(url) {
    const response = await send(url, {}, 180_000);
    if (!response.ok) {
      throw new Lux3dError(`模型下载失败 (HTTP ${response.status})`, {
        code: "MODEL_DOWNLOAD",
        status: response.status,
        retryable: response.status >= 500,
      });
    }
    const declared = Number(response.headers.get("content-length") ?? 0);
    const buffer = declared > MAX_MODEL_BYTES ? null : Buffer.from(await response.arrayBuffer());
    if (!buffer || buffer.length > MAX_MODEL_BYTES) {
      throw new Lux3dError("模型文件过大，已放弃下载", { code: "MODEL_TOO_LARGE" });
    }
    return buffer;
  }

  return {
    uploadImage,
    createImageTo3d,
    createImageTo3dFromViews,
    createFourView,
    createMultimodalToImage,
    createTextTo3d,
    createRig,
    createRetarget,
    getTask,
    downloadModel,
  };
}
