import crypto from "node:crypto";
import fs from "node:fs";
import express from "express";
import multer from "multer";
import { createBattleManager } from "./battles.mjs";
import { isGlb } from "./glb.mjs";
import { sniffImage } from "./image.mjs";
import { INPUT_KINDS, STYLES, createJobManager, toPublicJob } from "./jobs.mjs";
import { RemoteImageError, parseRemoteUrl } from "./remote-image.mjs";
import { isUuid } from "./store.mjs";

function safeEqual(a, b) {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function cleanName(value) {
  return Array.from(String(value ?? "").trim()).slice(0, 12).join("") || "团团";
}

export function createApp({ config, store, lux3d, battles = createBattleManager(), log = console, sleepImpl, fetchImage }) {
  const app = express();
  // Without this every request behind a reverse proxy shares the proxy's IP and the
  // per-IP daily quota would apply to all players at once.
  if (config.trustProxy) app.set("trust proxy", true);
  const jobs = createJobManager({ config, store, lux3d, log, sleepImpl, fetchImage });
  const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: config.limits.maxUploadBytes, files: 1 } });
  // Battle rooms exchange whole GLBs (10-20 MB), which is far above the photo limit.
  const battleUpload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 40 * 1024 * 1024, files: 1 } });

  app.disable("x-powered-by");
  app.use((_req, res, next) => {
    res.set("X-Content-Type-Options", "nosniff");
    next();
  });

  app.get("/health", (_req, res) => {
    res.json({
      ok: true,
      service: "animo-api",
      demoMode: config.demoMode,
      ...jobs.stats(),
      battles: battles.stats(),
    });
  });

  // Shared-secret gate. Without it anyone who can reach the server can spend the Lux3D credits.
  app.use("/api", (req, res, next) => {
    if (!config.clientSecret) return next();
    if (safeEqual(String(req.get("x-animo-secret") ?? ""), config.clientSecret)) return next();
    return res.status(401).json({ error: "unauthorized", message: "缺少或无效的访问密钥" });
  });

  // Create a pet from one of three inputs:
  //   photo : multipart field "image"            (a picture of a real object)
  //   url   : field "imageUrl"                   (a character image from the web)
  //   text  : field "prompt"                     (a description, no image needed)
  // Optional "style": "plush" restyles flat art into a chubby plush toy first (default for url);
  // "faithful" sends the image as-is (default for photo).
  app.post("/api/v1/pets", upload.single("image"), express.urlencoded({ extended: false, limit: "16kb" }), express.json({ limit: "16kb" }), (req, res) => {
    const body = req.body ?? {};
    const kind = String(body.kind ?? (req.file ? "photo" : body.imageUrl ? "url" : body.prompt ? "text" : "")).toLowerCase();
    if (!INPUT_KINDS.includes(kind)) {
      return res.status(400).json({ error: "input_required", message: "请提供照片、图片链接或文字描述" });
    }

    let input;
    if (kind === "photo") {
      if (!req.file) return res.status(400).json({ error: "image_required", message: "请上传一张图片" });
      if (!sniffImage(req.file.buffer)) {
        return res.status(415).json({ error: "unsupported_image", message: "仅支持 JPG、PNG、WebP 或 GIF 图片" });
      }
      input = { kind, buffer: req.file.buffer };
    } else if (kind === "url") {
      try {
        input = { kind, url: parseRemoteUrl(body.imageUrl).toString() };
      } catch (error) {
        if (error instanceof RemoteImageError) return res.status(400).json({ error: error.code, message: error.message });
        throw error;
      }
    } else {
      const prompt = String(body.prompt ?? "").trim();
      if (prompt.length < 2 || prompt.length > 200) {
        return res.status(400).json({ error: "bad_prompt", message: "文字描述需要 2 到 200 个字" });
      }
      input = { kind, prompt };
    }

    const style = body.style === undefined || body.style === "" ? undefined : String(body.style).toLowerCase();
    if (style !== undefined && !STYLES.includes(style)) {
      return res.status(400).json({ error: "bad_style", message: "style 只能是 plush 或 faithful" });
    }

    const result = jobs.submit({ name: cleanName(body.name), input, style, ip: req.ip });
    if (result.error === "rate-limited") {
      return res.status(429).json({ error: "rate_limited", message: "今天的创建次数已用完，明天再来吧" });
    }
    if (result.error === "busy") {
      res.set("Retry-After", "30");
      return res.status(503).json({ error: "busy", message: "现在排队的人有点多，请稍后再试" });
    }
    return res.status(202).location(`/api/v1/pets/${result.job.id}`).json(toPublicJob(result.job));
  });

  app.get("/api/v1/pets/:id", (req, res) => {
    const job = isUuid(req.params.id) ? jobs.get(req.params.id) : undefined;
    if (!job) return res.status(404).json({ error: "not_found", message: "找不到这个任务" });
    return res.set("Cache-Control", "no-store").json(toPublicJob(job));
  });

  app.get("/api/v1/models/:file", (req, res) => {
    const match = /^([0-9a-f-]{36})\.glb$/i.exec(req.params.file);
    const job = match && isUuid(match[1]) ? jobs.get(match[1]) : undefined;
    if (!job || job.status !== "succeeded" || job.demo) {
      return res.status(404).json({ error: "not_found", message: "模型不存在" });
    }
    const file = store.modelPath(job.id);
    if (!fs.existsSync(file)) return res.status(404).json({ error: "not_found", message: "模型不存在" });
    res.set("Cache-Control", "private, max-age=86400");
    return res.type("model/gltf-binary").sendFile(file);
  });

  // --- battle rooms -------------------------------------------------------
  function battleFailure(res, error) {
    if (error === "not_found") return res.status(404).json({ error, message: "房间不存在或已过期" });
    if (error === "full") return res.status(409).json({ error, message: "房间已满" });
    if (error === "waiting") return res.status(409).json({ error, message: "对手还没加入" });
    if (error === "forbidden") return res.status(403).json({ error, message: "只有房主可以开始对战" });
    if (error === "model_too_large") return res.status(413).json({ error, message: "精灵模型太大" });
    return res.status(400).json({ error: String(error) });
  }

  // Optional pet model; both sides upload their own so the peer can render it.
  function battleModel(req) {
    if (!req.file) return { buffer: null };
    if (!isGlb(req.file.buffer)) return { error: "bad_model" };
    return { buffer: req.file.buffer };
  }

  app.post("/api/v1/battles", battleUpload.single("model"), (req, res) => {
    const model = battleModel(req);
    if (model.error) return res.status(415).json({ error: "bad_model", message: "精灵模型不是有效 GLB" });
    const result = battles.create({ name: cleanName(req.body?.name), model: model.buffer });
    if (result.error) return battleFailure(res, result.error);
    return res.status(201).json(result.view);
  });

  app.post("/api/v1/battles/:code/join", battleUpload.single("model"), (req, res) => {
    const model = battleModel(req);
    if (model.error) return res.status(415).json({ error: "bad_model", message: "精灵模型不是有效 GLB" });
    const result = battles.join(String(req.params.code), { name: cleanName(req.body?.name), model: model.buffer });
    if (result.error) return battleFailure(res, result.error);
    return res.json(result.view);
  });

  app.get("/api/v1/battles/:code", (req, res) => {
    const side = req.query.side === "guest" ? "guest" : "host";
    const result = battles.poll(String(req.params.code), side);
    if (result.error) return battleFailure(res, result.error);
    return res.set("Cache-Control", "no-store").json(result.view);
  });

  // Host-only: publish one shared start timestamp so both terminals begin together.
  app.post("/api/v1/battles/:code/start", express.json({ limit: "4kb" }), (req, res) => {
    const side = req.body?.side === "guest" ? "guest" : "host";
    const result = battles.start(String(req.params.code), side, req.body?.leadMs);
    if (result.error) return battleFailure(res, result.error);
    return res.json(result.view);
  });

  // Host-only: the Cloud Anchor id that puts both terminals in the same physical space.
  app.post("/api/v1/battles/:code/anchor", express.json({ limit: "4kb" }), (req, res) => {
    const side = req.body?.side === "guest" ? "guest" : "host";
    const result = battles.setAnchor(String(req.params.code), side, req.body?.cloudAnchorId);
    if (result.error === "bad_anchor") {
      return res.status(400).json({ error: "bad_anchor", message: "Cloud Anchor id 无效" });
    }
    if (result.error) return battleFailure(res, result.error);
    return res.json(result.view);
  });

  app.get("/api/v1/battles/:code/model/:side", (req, res) => {
    const side = req.params.side === "guest" ? "guest" : "host";
    const model = battles.model(String(req.params.code), side);
    if (!model) return res.status(404).json({ error: "not_found", message: "对手模型还没有上传" });
    res.set("Cache-Control", "no-store");
    return res.type("model/gltf-binary").send(model);
  });

  app.delete("/api/v1/battles/:code", (req, res) => {
    battles.close(String(req.params.code));
    return res.status(204).end();
  });

  // eslint-disable-next-line no-unused-vars
  app.use((error, req, res, _next) => {
    if (error instanceof multer.MulterError) {
      const tooLarge = error.code === "LIMIT_FILE_SIZE";
      const isBattle = req.path.startsWith("/api/v1/battles");
      return res.status(tooLarge ? 413 : 400).json({
        error: tooLarge ? (isBattle ? "model_too_large" : "image_too_large") : "bad_upload",
        message: tooLarge
          ? isBattle ? "精灵模型太大了（上限 40MB）" : "图片太大了，请压缩后再试"
          : "上传格式不正确",
      });
    }
    if (error?.type === "entity.parse.failed" || error?.type === "entity.too.large") {
      return res.status(400).json({ error: "bad_request", message: "请求内容格式不正确" });
    }
    log.error("未处理的错误:", error);
    return res.status(500).json({ error: "internal", message: "服务器开小差了，请稍后再试" });
  });

  app.locals.jobs = jobs;
  return app;
}
