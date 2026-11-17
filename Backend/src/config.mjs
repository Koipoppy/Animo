import path from "node:path";
import { fileURLToPath } from "node:url";

export const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

// Node 22 reads .env natively, so no dotenv dependency is needed.
export function loadEnvFile(file = path.join(backendRoot, ".env")) {
  try {
    process.loadEnvFile(file);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
}

function readInt(value, fallback, min, max) {
  const parsed = Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export function loadConfig(env = process.env) {
  const apiKey = String(env.LUX3D_API_KEY ?? "").trim();
  if (!apiKey && env.ANIMO_ALLOW_DEMO === "false") {
    throw new Error("LUX3D_API_KEY 未配置，且 ANIMO_ALLOW_DEMO=false，服务无法启动");
  }
  return {
    port: readInt(env.PORT, 8787, 1, 65535),
    // Loopback by default: reach it from a USB-connected phone with `adb reverse tcp:8787 tcp:8787`.
    host: String(env.HOST ?? "").trim() || "127.0.0.1",
    // Behind Caddy/Nginx: trust X-Forwarded-For so per-IP quotas see real client IPs.
    trustProxy: env.TRUST_PROXY === "1" || env.TRUST_PROXY === "true",
    demoMode: !apiKey,
    clientSecret: String(env.CLIENT_SHARED_SECRET ?? "").trim(),
    dataDir: path.resolve(backendRoot, env.DATA_DIR ?? "data"),
    lux3d: {
      baseUrl: String(env.LUX3D_BASE_URL ?? "https://api.aholo3d.cn").replace(/\/+$/, ""),
      apiKey,
      version: env.LUX3D_VERSION === "G1" ? "G1" : "G1-Turbo",
      faceCount: readInt(env.LUX3D_FACE_COUNT, 30000, 10000, 300000),
      taskTimeoutMs: readInt(env.LUX3D_TASK_TIMEOUT_SECONDS, 900, 60, 3600) * 1000,
      // Rig + retarget took ~99 s in the real run; 8 min leaves room for the queue.
      rigTimeoutMs: readInt(env.LUX3D_RIG_TIMEOUT_SECONDS, 480, 60, 1800) * 1000,
    },
    limits: {
      maxUploadBytes: readInt(env.MAX_UPLOAD_MB, 10, 1, 50) * 1024 * 1024,
      maxQueued: readInt(env.MAX_QUEUED_JOBS, 20, 1, 500),
      concurrency: readInt(env.JOB_CONCURRENCY, 1, 1, 8),
      perIpPerDay: readInt(env.MAX_JOBS_PER_IP_PER_DAY, 30, 1, 10000),
    },
  };
}
