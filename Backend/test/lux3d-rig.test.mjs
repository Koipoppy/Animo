import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { inspectGlb } from "../src/glb.mjs";
import { ANIMATION_PRESETS } from "../src/jobs.mjs";
import { fakeLux3d, makeAnimatedGlb, makeJpeg, makeServer, photoForm, waitForJob } from "./helpers.mjs";

// Keep the Blender fallback deterministic: point it at a path that cannot exist.
function withoutBlender() {
  const previous = process.env.BLENDER_PATH;
  process.env.BLENDER_PATH = path.join(os.tmpdir(), "animo-no-blender", "blender");
  return () => {
    if (previous === undefined) delete process.env.BLENDER_PATH;
    else process.env.BLENDER_PATH = previous;
  };
}

test("cloud rig: Lux3D rig + retarget clips are renamed and served", async () => {
  const restore = withoutBlender();
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { name: "奶蛙" }) });
    assert.equal(response.status, 202);
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.stats.engine, "lux3d");
    assert.equal(done.stats.rigged, true);
    assert.equal(done.stats.skins, 1);
    assert.equal(done.stats.animations, ANIMATION_PRESETS.length);
    assert.deepEqual(done.stats.animationNames, ANIMATION_PRESETS);

    assert.deepEqual(lux3d.calls.rig, ["https://cdn.example/model.glb"]);
    assert.deepEqual(lux3d.calls.retarget, [{
      rigModelUrl: "https://cdn.example/rigged.glb",
      animationIds: ANIMATION_PRESETS,
    }]);
    assert.ok(lux3d.calls.download.includes("https://cdn.example/animated.glb"));
    assert.ok(!lux3d.calls.download.includes("https://cdn.example/model.glb"), "the raw model must not be downloaded when cloud rigging succeeds");

    const served = Buffer.from(await (await fetch(`${server.base}${done.modelUrl}`)).arrayBuffer());
    assert.deepEqual(inspectGlb(served).animationNames, ANIMATION_PRESETS);
  } finally {
    await server.close();
    restore();
  }
});

test("cloud rig: a non-riggable model falls back to the static mesh", async () => {
  const restore = withoutBlender();
  const lux3d = fakeLux3d({
    async createRig() {
      throw new Error("MODEL_NOT_RIGGABLE");
    },
  });
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { name: "杯子" }) });
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.match(done.stats.cloudRigSkipped, /MODEL_NOT_RIGGABLE/);
    assert.equal(done.stats.engine, undefined);
    assert.equal(done.stats.skins, 0);
    assert.deepEqual(lux3d.calls.download, ["https://cdn.example/model.glb"]);
  } finally {
    await server.close();
    restore();
  }
});

test("cloud rig: retarget failure also falls back without failing the job", async () => {
  const restore = withoutBlender();
  const lux3d = fakeLux3d({
    async createRetarget() {
      throw new Error("retarget service unavailable");
    },
  });
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { name: "团子" }) });
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.match(done.stats.cloudRigSkipped, /retarget service unavailable/);
    assert.equal(done.stats.animations, 0);
    assert.deepEqual(lux3d.calls.download, ["https://cdn.example/model.glb"]);
  } finally {
    await server.close();
    restore();
  }
});

test("cloud rig: successful actions are kept when part of the retarget fails", async () => {
  const restore = withoutBlender();
  const lux3d = fakeLux3d({
    async getTask(id) {
      if (id !== 777) return { status: 3, outputs: [{ content: "https://cdn.example/rigged.glb" }] };
      return {
        status: 3,
        outputs: [{
          content: JSON.stringify({
            actions: [
              { animation_id: "Idle_Loop", status: "success", animationGlbUrl: "https://cdn.example/partial.glb" },
              { animation_id: "Walk_Loop", status: "success", animationGlbUrl: "https://cdn.example/partial.glb" },
              { animation_id: "Punch_Jab", status: "failed", error: { code: "ANIMATION_RETARGET_FAILED" } },
            ],
          }),
        }],
      };
    },
    async downloadModel(url) {
      if (url === "https://cdn.example/partial.glb") return makeAnimatedGlb(["animation_1", "animation_2"]);
      return makeAnimatedGlb(["Idle_Loop"]);
    },
  });
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { name: "小鲸鱼" }) });
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.stats.engine, "lux3d");
    assert.equal(done.stats.animations, 2);
    assert.deepEqual(done.stats.animationNames, ["Idle_Loop", "Walk_Loop"]);
  } finally {
    await server.close();
    restore();
  }
});
