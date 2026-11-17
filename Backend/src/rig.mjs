/**
 * Optional post-processing: give a generated GLB a skeleton and an idle animation.
 *
 * Lux3D returns a single mesh with no skin, so nothing can be animated client-side. Blender is
 * driven headless to fit a humanoid template, bind it with distance-weighted skinning and bake an
 * idle loop. Everything is best-effort: if Blender is missing or fails, the caller keeps the
 * original static model rather than failing the job.
 */
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.resolve(here, "../../tools/blender/auto_rig.py");

const CANDIDATES = [
  "C:/Users/chenyuchong/MyApp/Blender/blender-5.2.2-windows-x64/blender.exe",
  "C:/Users/chenyuchong/dev/blender-dl/blender-4.5.9-windows-x64/blender.exe",
];

function findBlender() {
  for (const candidate of CANDIDATES) {
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

function blenderPath() {
  return process.env.BLENDER_PATH || findBlender();
}

function runBlender(blender, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(blender, args, { windowsHide: true });
    let output = "";
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("Blender 超时"));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => (output += chunk));
    child.stderr.on("data", (chunk) => (output += chunk));
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0 && output.includes("RIGGED")) resolve(output);
      else reject(new Error(`Blender 退出码 ${code}`));
    });
  });
}

/**
 * @param {Buffer} glb original model bytes
 * @param {"idle"|"walk"} mode
 * @returns {Promise<Buffer|null>} rigged GLB, or null when rigging is unavailable/failed
 */
export async function rigGlb(glb, mode = "idle") {
  const blender = blenderPath();
  if (!blender) return null;

  const dir = await mkdtemp(path.join(tmpdir(), "animo-rig-"));
  const input = path.join(dir, "in.glb");
  const output = path.join(dir, "out.glb");

  try {
    await writeFile(input, glb);
    await runBlender(
      blender,
      ["--background", "--python", scriptPath, "--", input, output, mode],
      Number(process.env.RIG_TIMEOUT_MS || 180_000),
    );
    if (!existsSync(output)) return null;
    return await readFile(output);
  } catch (error) {
    console.warn(`自动绑骨跳过：${error.message}`);
    return null;
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}

export function riggingAvailable() {
  return Boolean(blenderPath());
}
