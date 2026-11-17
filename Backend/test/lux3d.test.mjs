import assert from "node:assert/strict";
import { test } from "node:test";
import { Lux3dError, createLux3dClient, pickGlbUrl } from "../src/lux3d.mjs";

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function makeClient(handler) {
  const requests = [];
  const fetchImpl = async (url, init = {}) => {
    const record = { url: String(url), method: init.method ?? "GET", headers: init.headers ?? {}, body: init.body };
    requests.push(record);
    return handler(record, requests);
  };
  const client = createLux3dClient({ baseUrl: "https://api.test", apiKey: "SECRET-KEY", fetchImpl, sleepImpl: async () => {} });
  return { client, requests };
}

test("pickGlbUrl prefers a .glb URL and ignores non-URL placeholders", () => {
  assert.equal(
    pickGlbUrl([{ content: "https://cdn/x/results.zip" }, { content: "https://cdn/x/result.glb?sig=1" }, { content: "NOT_REQUESTED" }]),
    "https://cdn/x/result.glb?sig=1",
  );
  assert.equal(pickGlbUrl([{ content: "https://cdn/x/opaque" }]), "https://cdn/x/opaque");
  assert.equal(pickGlbUrl([{ content: "https://cdn/a.zip" }, { content: "https://cdn/b.zip" }]), null);
  assert.equal(pickGlbUrl(undefined), null);
});

test("API key is sent raw in Authorization with no Bearer prefix", async () => {
  const { client, requests } = makeClient(() => json({ c: "0", d: 42 }));
  assert.equal(await client.createImageTo3d("https://img/a.jpg"), 42);
  assert.equal(requests[0].headers.Authorization, "SECRET-KEY");
  const body = JSON.parse(requests[0].body);
  assert.deepEqual(
    { img: body.img, version: body.version, faceCount: body.faceCount, outputFormat: body.outputFormat, enablePbr: body.enablePbr },
    { img: "https://img/a.jpg", version: "G1-Turbo", faceCount: 30000, outputFormat: ["glb"], enablePbr: true },
  );
});

test("text-to-3d defaults to cartoon style", async () => {
  const { client, requests } = makeClient(() => json({ c: "0", d: 7 }));
  await client.createTextTo3d("一只圆滚滚的蓝色小鲸鱼");
  const body = JSON.parse(requests[0].body);
  assert.equal(body.style, "cartoon");
  assert.equal(body.prompt, "一只圆滚滚的蓝色小鲸鱼");
});

test("concurrency-limit and 5xx errors are retryable, auth errors are not", async () => {
  const limited = makeClient(() => json({ c: "GENERATION_CONCURRENCY_LIMIT_EXCEEDED", m: "busy", d: null }));
  await assert.rejects(limited.client.createImageTo3d("u"), (error) => error instanceof Lux3dError && error.retryable);
  const server = makeClient(() => json({ c: "-1", m: "boom" }, 500));
  await assert.rejects(server.client.createImageTo3d("u"), (error) => error.retryable === true);
  const auth = makeClient(() => json({ c: "-1", m: "appKey missing" }, 401));
  await assert.rejects(auth.client.createImageTo3d("u"), (error) => error.retryable === false && error.code === "LUX3D_AUTH");
});

test("small images use single upload then poll status until success", async () => {
  let polls = 0;
  const { client, requests } = makeClient((request) => {
    if (request.url.endsWith("/asset/v1/token")) {
      return json({ ousToken: "T", globalDomain: "https://ous.test", blockSize: 1024 });
    }
    if (request.url.endsWith("/ous/api/v2/single/upload")) return json({ c: "0", d: { taskId: "u1" } });
    if (request.url.endsWith("/ous/api/v2/upload/status")) {
      polls += 1;
      return json({ c: "0", d: polls < 3 ? { status: 4 } : { status: 5, url: "https://cdn/final.jpg" } });
    }
    throw new Error(`unexpected ${request.url}`);
  });
  const url = await client.uploadImage({ buffer: Buffer.alloc(500, 1), filename: "a.jpg", contentType: "image/jpeg" });
  assert.equal(url, "https://cdn/final.jpg");
  const upload = requests.find((r) => r.url.endsWith("/single/upload"));
  assert.equal(upload.headers["ous-token-v2"], "T");
  assert.equal(upload.headers.Authorization, undefined, "the API key must not be sent to the upload host");
  assert.equal(polls, 3);
});

test("large images use block upload with 1-based part numbers", async () => {
  const parts = [];
  const { client } = makeClient(async (request) => {
    if (request.url.endsWith("/asset/v1/token")) return json({ ousToken: "T", globalDomain: "https://ous.test", blockSize: 1000 });
    if (request.url.includes("/block/upload/init")) {
      const query = new URL(request.url).searchParams;
      assert.equal(query.get("blocks"), "3");
      assert.equal(query.get("size"), "2500");
      assert.match(query.get("md5"), /^[0-9a-f]{32}$/);
      return json({ c: "0", d: { taskId: 9, deduplicated: false } });
    }
    if (request.url.endsWith("/block/upload/part")) {
      parts.push(request.body.get("block"));
      return json({ c: "0", d: null });
    }
    if (request.url.endsWith("/upload/status")) return json({ c: "0", d: { status: 5, url: "https://cdn/big.jpg" } });
    throw new Error(`unexpected ${request.url}`);
  });
  assert.equal(await client.uploadImage({ buffer: Buffer.alloc(2500, 2), filename: "b.jpg", contentType: "image/jpeg" }), "https://cdn/big.jpg");
  assert.deepEqual(parts, ["1", "2", "3"]);
});

test("a failed upload maps the content-safety code to a friendly message", async () => {
  const { client } = makeClient((request) => {
    if (request.url.endsWith("/asset/v1/token")) return json({ ousToken: "T", globalDomain: "https://ous.test", blockSize: 1024 });
    if (request.url.endsWith("/single/upload")) return json({ c: "0", d: { taskId: "u1" } });
    return json({ c: "0", d: { status: 6, errorCode: 5 } });
  });
  await assert.rejects(client.uploadImage({ buffer: Buffer.alloc(10), filename: "a.jpg", contentType: "image/jpeg" }), /内容安全/);
});

test("model download never sends the API key and enforces a size cap", async () => {
  const { client, requests } = makeClient(() => new Response(Buffer.from("glb-bytes"), { status: 200 }));
  assert.equal((await client.downloadModel("https://cdn/model.glb")).toString(), "glb-bytes");
  assert.equal(requests[0].headers.Authorization, undefined);

  const huge = makeClient(() => new Response("x", { status: 200, headers: { "content-length": String(200 * 1024 * 1024) } }));
  await assert.rejects(huge.client.downloadModel("https://cdn/huge.glb"), (error) => error.code === "MODEL_TOO_LARGE");
});
