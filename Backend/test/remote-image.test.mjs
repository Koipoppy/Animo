import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { RemoteImageError, createSafeLookup, extractPageImage, fetchRemoteImage, isPublicAddress, parseRemoteUrl } from "../src/remote-image.mjs";

test("isPublicAddress rejects loopback, private, link-local and metadata ranges", () => {
  for (const blocked of [
    "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
    "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1",
    "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
  ]) {
    assert.equal(isPublicAddress(blocked), false, `${blocked} should be blocked`);
  }
});

test("isPublicAddress allows ordinary public addresses", () => {
  for (const allowed of ["8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"]) {
    assert.equal(isPublicAddress(allowed), true, `${allowed} should be allowed`);
  }
});

test("parseRemoteUrl only accepts http(s) without credentials", () => {
  assert.equal(parseRemoteUrl("https://example.com/a.png").hostname, "example.com");
  for (const bad of ["ftp://example.com/a.png", "file:///etc/passwd", "javascript:alert(1)", "not a url", "https://u:p@example.com/a.png"]) {
    assert.throws(() => parseRemoteUrl(bad), RemoteImageError, bad);
  }
});

test("fetchRemoteImage refuses literal private hosts without making a request", async () => {
  let called = false;
  const fetchImpl = async () => {
    called = true;
    return new Response("x");
  };
  for (const url of ["http://127.0.0.1/a.png", "http://169.254.169.254/latest/meta-data", "http://[::1]/a.png", "http://10.0.0.5/a.png"]) {
    await assert.rejects(fetchRemoteImage(url, { fetchImpl }), (error) => error.code === "blocked_host", url);
  }
  assert.equal(called, false);
});

test("fetchRemoteImage re-validates every redirect hop", async () => {
  const seen = [];
  const fetchImpl = async (url) => {
    seen.push(String(url));
    return new Response(null, { status: 302, headers: { location: "http://127.0.0.1:8080/secret" } });
  };
  await assert.rejects(fetchRemoteImage("http://8.8.8.8/start.png", { fetchImpl }), (error) => error.code === "blocked_host");
  assert.deepEqual(seen, ["http://8.8.8.8/start.png"]);
});

test("fetchRemoteImage rejects HTML pages with a helpful message", async () => {
  const fetchImpl = async () => new Response("<html></html>", { status: 200, headers: { "content-type": "text/html; charset=utf-8" } });
  await assert.rejects(fetchRemoteImage("http://8.8.8.8/page", { fetchImpl }), (error) => error.code === "not_image");
});

test("fetchRemoteImage enforces the size limit on streamed bodies", async () => {
  const big = new Uint8Array(13 * 1024 * 1024);
  const fetchImpl = async () => new Response(big, { status: 200, headers: { "content-type": "image/png" } });
  await assert.rejects(fetchRemoteImage("http://8.8.8.8/big.png", { fetchImpl }), (error) => error.code === "too_large");
});

test("hostnames that resolve to private addresses are blocked in the pre-flight check", async () => {
  const lookupImpl = (_host, _options, callback) => callback(null, [{ address: "10.0.0.7", family: 4 }]);
  let called = false;
  const fetchImpl = async () => {
    called = true;
    return new Response("x");
  };
  await assert.rejects(fetchRemoteImage("http://evil.example/a.png", { fetchImpl, lookupImpl }), (error) => error.code === "blocked_host");
  assert.equal(called, false);
});

test("a mixed public+private DNS answer is rejected", async () => {
  const lookupImpl = (_host, _options, callback) =>
    callback(null, [{ address: "8.8.8.8", family: 4 }, { address: "192.168.0.9", family: 4 }]);
  await assert.rejects(
    fetchRemoteImage("http://mixed.example/a.png", { fetchImpl: async () => new Response("x"), lookupImpl }),
    (error) => error.code === "blocked_host",
  );
});

test("safe lookup re-validates at connect time (defeats DNS rebinding)", async () => {
  const rebinding = createSafeLookup((_host, _options, callback) => callback(null, [{ address: "127.0.0.1", family: 4 }]));
  const error = await new Promise((resolve) => rebinding("rebind.example", {}, (e) => resolve(e)));
  assert.equal(error.code, "blocked_host");

  const good = createSafeLookup((_host, _options, callback) => callback(null, [{ address: "8.8.8.8", family: 4 }]));
  const single = await new Promise((resolve) => good("ok.example", {}, (e, address, family) => resolve({ e, address, family })));
  assert.deepEqual(single, { e: null, address: "8.8.8.8", family: 4 });
  const all = await new Promise((resolve) => good("ok.example", { all: true }, (e, list) => resolve(list)));
  assert.deepEqual(all, [{ address: "8.8.8.8", family: 4 }]);
});

test("the guarded fetch really refuses to connect to a loopback server (real socket)", async () => {
  const hit = [];
  const server = http.createServer((_req, res) => {
    hit.push(1);
    res.end("secret");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    // "localhost" looks harmless by name but resolves to loopback; the safe lookup must stop it.
    const url = `http://localhost:${server.address().port}/a.png`;
    await assert.rejects(fetchRemoteImage(url), (error) => error.code === "blocked_host");
    assert.equal(hit.length, 0, "the loopback server must never receive a request");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("extractPageImage prefers og:image, then twitter:image, then image_src", () => {
  const page = "https://post.example.com/p/123";
  const cases = [
    [`<meta property="og:image" content="https://img.example.com/a.jpg">`, "https://img.example.com/a.jpg"],
    [`<meta content='https://img.example.com/b.jpg' property='og:image'>`, "https://img.example.com/b.jpg"],
    [`<meta name="twitter:image" content="https://img.example.com/c.jpg">`, "https://img.example.com/c.jpg"],
    [`<meta property="og:image" content="/rel/d.jpg">`, "https://post.example.com/rel/d.jpg"],
    [`<link rel="image_src" href="https://img.example.com/e.jpg">`, "https://img.example.com/e.jpg"],
  ];
  for (const [html, expected] of cases) assert.equal(extractPageImage(html, page)?.toString(), expected, html);
  assert.equal(extractPageImage("<html><body>no picture</body></html>", page), null);
  assert.equal(extractPageImage(`<meta property="og:image" content="file:///etc/passwd">`, page), null, "non-http schemes are refused");
});

test("a post link is followed to the page's preview image", async () => {
  const seen = [];
  const fetchImpl = async (url) => {
    seen.push(String(url));
    if (String(url) === "https://post.example.com/p/123") {
      return new Response(
        `<html><head><meta property="og:image" content="https://img.example.com/mascot.png"></head></html>`,
        { status: 200, headers: { "content-type": "text/html; charset=utf-8" } },
      );
    }
    return new Response(Buffer.from([9, 9, 9]), { status: 200, headers: { "content-type": "image/png" } });
  };
  const body = await fetchRemoteImage("https://post.example.com/p/123", {
    fetchImpl,
    lookupImpl: (_h, _o, cb) => cb(null, [{ address: "8.8.8.8", family: 4 }]),
  });
  assert.deepEqual([...body], [9, 9, 9]);
  assert.deepEqual(seen, ["https://post.example.com/p/123", "https://img.example.com/mascot.png"]);
});

test("a page without a preview image still fails with advice", async () => {
  const fetchImpl = async () => new Response("<html>nothing</html>", { status: 200, headers: { "content-type": "text/html" } });
  await assert.rejects(
    fetchRemoteImage("https://post.example.com/x", { fetchImpl, lookupImpl: (_h, _o, cb) => cb(null, [{ address: "8.8.8.8", family: 4 }]) }),
    (error) => error.code === "not_image" && /长按图片/.test(error.message),
  );
});

test("fetchRemoteImage returns the body for a normal image", async () => {
  const fetchImpl = async () => new Response(Buffer.from([1, 2, 3, 4]), { status: 200, headers: { "content-type": "image/png" } });
  const body = await fetchRemoteImage("http://8.8.8.8/ok.png", { fetchImpl });
  assert.deepEqual([...body], [1, 2, 3, 4]);
});

test("fetchRemoteImage stops after too many redirects", async () => {
  const fetchImpl = async () => new Response(null, { status: 302, headers: { location: "http://8.8.4.4/next" } });
  await assert.rejects(fetchRemoteImage("http://8.8.8.8/start", { fetchImpl }), (error) => error.code === "too_many_redirects");
});
