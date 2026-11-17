import assert from "node:assert/strict";
import { test } from "node:test";
import { makeGlb, makeServer } from "./helpers.mjs";

function battleForm(name, model) {
  const form = new FormData();
  form.append("name", name);
  if (model) form.append("model", new Blob([model], { type: "model/gltf-binary" }), "pet.glb");
  return form;
}

test("battle rooms: host creates, guest joins, shared start time is published", async () => {
  const server = await makeServer();
  try {
    const hostModel = makeGlb([0.2, 0.2, 0.1]);
    const created = await fetch(`${server.base}/api/v1/battles`, { method: "POST", body: battleForm("伽拉忒亚", hostModel) });
    assert.equal(created.status, 201);
    const hosting = await created.json();
    assert.match(hosting.code, /^\d{4}$/);
    assert.equal(hosting.side, "host");
    assert.equal(hosting.status, "waiting");
    assert.equal(hosting.peer, null);
    assert.equal(hosting.self.hasModel, true);

    // Guest joins with its own pet model.
    const guestModel = makeGlb([0.3, 0.1, 0.1]);
    const joinedRes = await fetch(`${server.base}/api/v1/battles/${hosting.code}/join`, {
      method: "POST",
      body: battleForm("对手", guestModel),
    });
    assert.equal(joinedRes.status, 200);
    const joined = await joinedRes.json();
    assert.equal(joined.side, "guest");
    assert.equal(joined.status, "ready");
    assert.equal(joined.peer.name, "伽拉忒亚");

    // Host poll sees the guest.
    const hostPoll = (await fetch(`${server.base}/api/v1/battles/${hosting.code}?side=host`).then((r) => r.json()));
    assert.equal(hostPoll.peer.name, "对手");
    assert.equal(hostPoll.startAt, null);

    // Only the host may start; starting sets a shared epoch-ms timestamp ~2 s out.
    const guestStart = await fetch(`${server.base}/api/v1/battles/${hosting.code}/start`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ side: "guest" }),
    });
    assert.equal(guestStart.status, 403);

    const started = await fetch(`${server.base}/api/v1/battles/${hosting.code}/start`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ side: "host" }),
    }).then((r) => r.json());
    assert.equal(started.status, "starting");
    assert.ok(started.startAt > Date.now(), "startAt should be in the future");
    assert.ok(started.startAt <= Date.now() + 4000, "startAt should be ~2s out");

    // Both sides read the same startAt.
    const guestPoll = await fetch(`${server.base}/api/v1/battles/${hosting.code}?side=guest`).then((r) => r.json());
    assert.equal(guestPoll.startAt, started.startAt);

    // Each side can download the other pet's GLB.
    const peerModel = Buffer.from(await fetch(`${server.base}/api/v1/battles/${hosting.code}/model/host`).then((r) => r.arrayBuffer()));
    assert.deepEqual(peerModel, hostModel);
    const guestOnHost = Buffer.from(await fetch(`${server.base}/api/v1/battles/${hosting.code}/model/guest`).then((r) => r.arrayBuffer()));
    assert.deepEqual(guestOnHost, guestModel);
  } finally {
    await server.close();
  }
});

test("battle rooms: errors are explicit and stale codes 404", async () => {
  const server = await makeServer();
  try {
    const missing = await fetch(`${server.base}/api/v1/battles/9999?side=host`);
    assert.equal(missing.status, 404);

    const created = await fetch(`${server.base}/api/v1/battles`, { method: "POST", body: battleForm("团团", makeGlb()) }).then((r) => r.json());
    const again = await fetch(`${server.base}/api/v1/battles/${created.code}/join`, { method: "POST", body: battleForm("二号", makeGlb()) });
    assert.equal(again.status, 200);
    const third = await fetch(`${server.base}/api/v1/battles/${created.code}/join`, { method: "POST", body: battleForm("三号", makeGlb()) });
    assert.equal(third.status, 409, "a third terminal cannot join");

    const notReady = await fetch(`${server.base}/api/v1/battles/${created.code}/start`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ side: "host" }),
    });
    // Two players are already in, so start succeeds instead of waiting.
    assert.equal(notReady.status, 200);

    const badModel = new FormData();
    badModel.append("name", "坏模型");
    badModel.append("model", new Blob([Buffer.from("not-a-glb")], { type: "model/gltf-binary" }), "bad.glb");
    const rejected = await fetch(`${server.base}/api/v1/battles`, { method: "POST", body: badModel });
    assert.equal(rejected.status, 415);

    await fetch(`${server.base}/api/v1/battles/${created.code}`, { method: "DELETE" });
    const gone = await fetch(`${server.base}/api/v1/battles/${created.code}?side=host`);
    assert.equal(gone.status, 404);
  } finally {
    await server.close();
  }
});

test("battle rooms: waiting host cannot start before a guest joins", async () => {
  const server = await makeServer();
  try {
    const created = await fetch(`${server.base}/api/v1/battles`, { method: "POST", body: battleForm("团团", makeGlb()) }).then((r) => r.json());
    const waiting = await fetch(`${server.base}/api/v1/battles/${created.code}/start`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ side: "host" }),
    });
    assert.equal(waiting.status, 409);
    assert.equal((await waiting.json()).error, "waiting");
  } finally {
    await server.close();
  }
});
