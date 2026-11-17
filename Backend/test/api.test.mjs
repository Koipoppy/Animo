import assert from "node:assert/strict";
import { test } from "node:test";
import { isGlb } from "../src/glb.mjs";
import { RemoteImageError } from "../src/remote-image.mjs";
import { fakeLux3d, makeGlb, makeJpeg, makeServer, photoForm, waitForJob } from "./helpers.mjs";

test("demo mode: creating a pet never calls Lux3D and the job is flagged as demo", async () => {
  const server = await makeServer();
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { name: "团团" }) });
    assert.equal(response.status, 202);
    const created = await response.json();
    assert.equal(created.demo, true);
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded");
    assert.equal(done.source, "demo");
    assert.equal(done.modelUrl, undefined, "demo jobs have no downloadable model");
    assert.equal((await fetch(`${server.base}/api/v1/models/${created.id}.glb`)).status, 404);
  } finally {
    await server.close();
  }
});

test("photo input: normalized JPEG is uploaded, GLB is stored and served", async () => {
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(1200, 900), { name: "奶蛙" }) });
    assert.equal(response.status, 202);
    const created = await response.json();
    assert.equal(created.inputKind, "photo");
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.source, "lux3d");
    assert.equal(done.name, "奶蛙");
    assert.equal(done.stats.triangles, 1);
    assert.equal(lux3d.calls.upload[0].contentType, "image/jpeg");
    assert.deepEqual(lux3d.calls.image, ["https://cdn.example/uploaded.jpg"]);
    assert.equal(lux3d.calls.text.length, 0);

    const model = await fetch(`${server.base}${done.modelUrl}`);
    assert.equal(model.status, 200);
    assert.equal(model.headers.get("content-type"), "model/gltf-binary");
    const served = Buffer.from(await model.arrayBuffer());
    assert.equal(isGlb(served), true);
    assert.ok(served.length > 0);
  } finally {
    await server.close();
  }
});

test("url input: the server downloads the web image itself, then follows the same pipeline", async () => {
  const lux3d = fakeLux3d();
  const requested = [];
  const fetchImage = async (url) => {
    requested.push(url);
    return makeJpeg(500, 500, "#3366cc");
  };
  const server = await makeServer({ lux3d, fetchImage });
  try {
    const body = new URLSearchParams({ imageUrl: "https://img.example.com/frog.png", name: "奶蛙" });
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body });
    assert.equal(response.status, 202);
    const created = await response.json();
    assert.equal(created.inputKind, "url");
    assert.equal(JSON.stringify(created).includes("img.example.com"), false, "the source URL must not leak in responses");
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.deepEqual(requested, ["https://img.example.com/frog.png"]);
    assert.equal(lux3d.calls.upload.length, 1);
    // Web pictures go down the character pipeline: restyle, four views, then multi-image 3D.
    assert.equal(lux3d.calls.multiImage.length, 1);
  } finally {
    await server.close();
  }
});

test("url input: JSON bodies work and unreachable images fail the job with the reason", async () => {
  const fetchImage = async () => {
    throw new RemoteImageError("这是一个网页而不是图片，请复制图片本身的链接", "not_image");
  };
  const server = await makeServer({ lux3d: fakeLux3d(), fetchImage });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ imageUrl: "https://example.com/page" }),
    });
    assert.equal(response.status, 202);
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "failed");
    assert.match(done.error, /网页而不是图片/);
  } finally {
    await server.close();
  }
});

test("url input: bad schemes are rejected before a job is created", async () => {
  const server = await makeServer({ lux3d: fakeLux3d() });
  try {
    for (const imageUrl of ["file:///etc/passwd", "ftp://example.com/a.png", "nonsense"]) {
      const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl }) });
      assert.equal(response.status, 400, imageUrl);
    }
    assert.equal(server.app.locals.jobs.stats().tracked, 0);
  } finally {
    await server.close();
  }
});

test("url input defaults to plush: restyle, then four views, then multi-image 3D", async () => {
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d, fetchImage: async () => makeJpeg(500, 500) });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl: "https://img.example.com/mascot.png" }) });
    const created = await response.json();
    assert.equal(created.style, "plush");
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.style, "plush");
    assert.equal(done.views, true, "the job should report that it used four views");
    assert.equal(lux3d.calls.restyle.length, 1);
    assert.match(lux3d.calls.restyle[0].prompt, /毛绒/);
    assert.deepEqual(lux3d.calls.fourView, ["https://cdn.example/plush.jpg"], "four views come from the restyled image");
    assert.equal(lux3d.calls.multiImage.length, 1);
    assert.deepEqual(lux3d.calls.multiImage[0], [
      "https://cdn.example/v1.png", "https://cdn.example/v2.png", "https://cdn.example/v3.png", "https://cdn.example/v4.png",
    ]);
    assert.equal(lux3d.calls.image.length, 0, "the single-image path must not run when views succeed");
  } finally {
    await server.close();
  }
});

test("when the four-view step fails, the pet is still modelled from the single restyled image", async () => {
  const lux3d = fakeLux3d({
    async getTask(id) {
      if (id === 333) return { status: 3, outputs: [{ content: "https://cdn.example/plush.jpg" }] };
      if (id === 444) return { status: 4 }; // four views failed
      return { status: 3, outputs: [{ content: "https://cdn.example/model.glb" }] };
    },
  });
  const server = await makeServer({ lux3d, fetchImage: async () => makeJpeg(500, 500) });
  try {
    const created = await (await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl: "https://img.example.com/mascot.png" }) })).json();
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.views, false, "the job reports the single-image path");
    assert.deepEqual(lux3d.calls.image, ["https://cdn.example/plush.jpg"]);
  } finally {
    await server.close();
  }
});

test("photo input defaults to faithful: no restyle, no four views, the photo goes straight to 3D", async () => {
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d });
  try {
    const created = await (await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg()) })).json();
    assert.equal(created.style, "faithful");
    await waitForJob(server.base, created.id);
    assert.equal(lux3d.calls.restyle.length, 0);
    assert.equal(lux3d.calls.fourView.length, 0);
    assert.deepEqual(lux3d.calls.image, ["https://cdn.example/uploaded.jpg"]);
  } finally {
    await server.close();
  }
});

test("the style can be overridden per request, and unknown styles are rejected", async () => {
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d, fetchImage: async () => makeJpeg(500, 500) });
  try {
    const faithful = await (await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl: "https://img.example.com/a.png", style: "faithful" }) })).json();
    await waitForJob(server.base, faithful.id);
    assert.equal(lux3d.calls.restyle.length, 0);

    const photoPlush = await (await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: photoForm(await makeJpeg(), { style: "plush" }) })).json();
    await waitForJob(server.base, photoPlush.id);
    assert.equal(lux3d.calls.restyle.length, 1);

    const bad = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl: "https://img.example.com/a.png", style: "neon" }) });
    assert.equal(bad.status, 400);
  } finally {
    await server.close();
  }
});

test("if the plush restyle fails, the pet is still made from the original image", async () => {
  const lux3d = fakeLux3d({
    async getTask(id) {
      if (id === 333) return { status: 4 };
      return { status: 3, outputs: [{ content: "https://cdn.example/model.glb" }] };
    },
  });
  const server = await makeServer({ lux3d, fetchImage: async () => makeJpeg(500, 500) });
  try {
    const created = await (await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ imageUrl: "https://img.example.com/a.png" }) })).json();
    const done = await waitForJob(server.base, created.id);
    assert.equal(done.status, "succeeded", JSON.stringify(done));
    assert.equal(done.style, "faithful", "the job reports that it fell back");
    assert.deepEqual(lux3d.calls.image, ["https://cdn.example/uploaded.jpg"]);
  } finally {
    await server.close();
  }
});

test("text input goes through text-to-3d and needs no image", async () => {
  const lux3d = fakeLux3d();
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, {
      method: "POST",
      body: new URLSearchParams({ prompt: "一条戴着蓝色围巾的圆滚滚的小鲸鱼", name: "小鲸" }),
    });
    assert.equal(response.status, 202);
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded");
    assert.equal(done.inputKind, "text");
    assert.deepEqual(lux3d.calls.text, ["一条戴着蓝色围巾的圆滚滚的小鲸鱼"]);
    assert.equal(lux3d.calls.upload.length, 0);
  } finally {
    await server.close();
  }
});

test("validation: empty input, short prompt, non-image upload, and oversized upload", async () => {
  const server = await makeServer({ lux3d: fakeLux3d(), env: { MAX_UPLOAD_MB: "1" } });
  try {
    assert.equal((await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ name: "x" }) })).status, 400);
    assert.equal((await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "a" }) })).status, 400);
    const notImage = new FormData();
    notImage.append("image", new Blob(["<html>hi</html>"], { type: "image/jpeg" }), "x.jpg");
    assert.equal((await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: notImage })).status, 415);
    const huge = new FormData();
    huge.append("image", new Blob([Buffer.alloc(2 * 1024 * 1024, 0xff)], { type: "image/jpeg" }), "x.jpg");
    assert.equal((await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: huge })).status, 413);
  } finally {
    await server.close();
  }
});

test("shared secret: /api is locked when configured, /health stays open", async () => {
  const server = await makeServer({ lux3d: fakeLux3d(), env: { CLIENT_SHARED_SECRET: "s3cret" } });
  try {
    assert.equal((await fetch(`${server.base}/health`)).status, 200);
    const unauth = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    assert.equal(unauth.status, 401);
    const wrong = await fetch(`${server.base}/api/v1/pets`, { method: "POST", headers: { "x-animo-secret": "nope" }, body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    assert.equal(wrong.status, 401);
    const ok = await fetch(`${server.base}/api/v1/pets`, { method: "POST", headers: { "x-animo-secret": "s3cret" }, body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    assert.equal(ok.status, 202);
  } finally {
    await server.close();
  }
});

test("per-IP daily limit returns 429", async () => {
  const server = await makeServer({ lux3d: fakeLux3d(), env: { MAX_JOBS_PER_IP_PER_DAY: "2" } });
  try {
    const post = () => fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    assert.equal((await post()).status, 202);
    assert.equal((await post()).status, 202);
    assert.equal((await post()).status, 429);
  } finally {
    await server.close();
  }
});

test("Lux3D failure surfaces a message without internals; busy retries recover", async () => {
  let attempts = 0;
  const lux3d = fakeLux3d({
    async createTextTo3d() {
      attempts += 1;
      if (attempts < 3) throw Object.assign(new Error("busy"), { name: "Lux3dError", retryable: true });
      return 5;
    },
  });
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "succeeded");
    assert.equal(attempts, 3);
  } finally {
    await server.close();
  }

  const failing = fakeLux3d({
    async getTask() {
      return { status: 4 };
    },
  });
  const failServer = await makeServer({ lux3d: failing });
  try {
    const response = await fetch(`${failServer.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    const done = await waitForJob(failServer.base, (await response.json()).id);
    assert.equal(done.status, "failed");
    assert.match(done.error, /背景更简单/);
    assert.equal(JSON.stringify(done).includes("lux3dTaskId"), false);
  } finally {
    await failServer.close();
  }
});

test("a non-GLB download fails the job instead of storing garbage", async () => {
  const lux3d = fakeLux3d({
    async downloadModel() {
      return Buffer.from("<html>expired</html>");
    },
  });
  const server = await makeServer({ lux3d });
  try {
    const response = await fetch(`${server.base}/api/v1/pets`, { method: "POST", body: new URLSearchParams({ prompt: "小鲸鱼" }) });
    const done = await waitForJob(server.base, (await response.json()).id);
    assert.equal(done.status, "failed");
    assert.match(done.error, /GLB/);
    assert.equal((await fetch(`${server.base}/api/v1/models/${done.id}.glb`)).status, 404);
  } finally {
    await server.close();
  }
});

test("model and job routes reject malformed ids", async () => {
  const server = await makeServer();
  try {
    assert.equal((await fetch(`${server.base}/api/v1/pets/not-a-uuid`)).status, 404);
    assert.equal((await fetch(`${server.base}/api/v1/models/..%2F..%2Fsecret.glb`)).status, 404);
    assert.equal((await fetch(`${server.base}/api/v1/models/00000000-0000-4000-8000-000000000000.glb`)).status, 404);
  } finally {
    await server.close();
  }
});
