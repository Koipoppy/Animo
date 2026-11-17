/**
 * Battle rooms: pair two terminals, exchange their pet GLBs and agree on one start time.
 *
 * The battle itself is deterministic (fixed damage, fixed choreography), so both clients
 * run the same local logic from the same `startAt` and see the same fight. That avoids
 * streaming per-frame state and keeps working even if one link hiccups.
 */
import crypto from "node:crypto";

const ROOM_TTL_MS = 30 * 60 * 1000;
const MAX_MODEL_BYTES = 40 * 1024 * 1024;

export function createBattleManager({ now = Date.now } = {}) {
  const rooms = new Map();

  function sweep() {
    const t = now();
    for (const [code, room] of rooms) {
      if (t - room.createdAt > ROOM_TTL_MS) rooms.delete(code);
    }
  }

  function newCode() {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const code = String(1000 + Math.floor(Math.random() * 9000));
      if (!rooms.has(code)) return code;
    }
    throw new Error("no free room code");
  }

  function view(room, side) {
    const self = side === "host" ? room.host : room.guest;
    const peer = side === "host" ? room.guest : room.host;
    return {
      code: room.code,
      side,
      status: room.status,
      startAt: room.startAt,
      cloudAnchorId: room.cloudAnchorId ?? null,
      self: self ? { name: self.name, hasModel: Boolean(self.model) } : null,
      peer: peer ? { name: peer.name, hasModel: Boolean(peer.model) } : null,
    };
  }

  return {
    create({ name, model }) {
      sweep();
      if (model && model.length > MAX_MODEL_BYTES) return { error: "model_too_large" };
      const room = {
        code: newCode(),
        createdAt: now(),
        host: { name, model: model ?? null },
        guest: null,
        status: "waiting",
        startAt: null,
        cloudAnchorId: null,
      };
      rooms.set(room.code, room);
      return { room, view: view(room, "host") };
    },

    join(code, { name, model }) {
      sweep();
      const room = rooms.get(code);
      if (!room) return { error: "not_found" };
      if (room.guest) return { error: "full" };
      if (model && model.length > MAX_MODEL_BYTES) return { error: "model_too_large" };
      room.guest = { name, model: model ?? null };
      room.status = "ready";
      return { room, view: view(room, "guest") };
    },

    poll(code, side) {
      const room = rooms.get(code);
      if (!room) return { error: "not_found" };
      return { room, view: view(room, side) };
    },

    /**
     * Host-only: agree on a shared epoch-ms start. `leadMs` gives the guest time to resolve a
     * Cloud Anchor before the first move (clamped to 1-15 s).
     */
    start(code, side, leadMs = 2000) {
      const room = rooms.get(code);
      if (!room) return { error: "not_found" };
      if (side !== "host") return { error: "forbidden" };
      if (!room.guest) return { error: "waiting" };
      if (!room.startAt) {
        const lead = Math.max(1000, Math.min(15000, Number(leadMs) || 2000));
        room.startAt = now() + lead;
        room.status = "starting";
      }
      return { room, view: view(room, "host") };
    },

    /** Host-only: publish the Cloud Anchor id so the guest can resolve the same physical pose. */
    setAnchor(code, side, cloudAnchorId) {
      const room = rooms.get(code);
      if (!room) return { error: "not_found" };
      if (side !== "host") return { error: "forbidden" };
      const id = String(cloudAnchorId ?? "").trim();
      if (!id || id.length > 256) return { error: "bad_anchor" };
      room.cloudAnchorId = id;
      return { room, view: view(room, "host") };
    },

    /** GLB of one side's pet, so the other side can render the opponent. */
    model(code, side) {
      const room = rooms.get(code);
      if (!room) return null;
      const entry = side === "host" ? room.host : room.guest;
      return entry?.model ?? null;
    },

    close(code) {
      rooms.delete(code);
    },

    stats: () => ({ rooms: rooms.size }),
  };
}
