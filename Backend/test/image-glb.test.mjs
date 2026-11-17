import assert from "node:assert/strict";
import { test } from "node:test";
import sharp from "sharp";
import { inspectGlb, isGlb, renameGlbAnimations } from "../src/glb.mjs";
import { ImageError, normalizeImage, sniffImage } from "../src/image.mjs";
import { makeAnimatedGlb, makeGlb, makeJpeg, makeTransparentPng } from "./helpers.mjs";

test("sniffImage identifies formats by magic bytes, not by name", async () => {
  assert.equal(sniffImage(await makeJpeg())?.ext, "jpg");
  assert.equal(sniffImage(await makeTransparentPng())?.ext, "png");
  assert.equal(sniffImage(await sharp({ create: { width: 8, height: 8, channels: 3, background: "#fff" } }).webp().toBuffer())?.ext, "webp");
  assert.equal(sniffImage(Buffer.from("GIF89a....")).ext, "gif");
  assert.equal(sniffImage(Buffer.from("<html>")), null);
  assert.equal(sniffImage(Buffer.alloc(0)), null);
});

test("normalizeImage flattens transparency onto white and outputs JPEG", async () => {
  const out = await normalizeImage(await makeTransparentPng());
  assert.equal(out.contentType, "image/jpeg");
  assert.equal(sniffImage(out.buffer).ext, "jpg");
  const { data } = await sharp(out.buffer).raw().toBuffer({ resolveWithObject: true });
  assert.ok(data[0] > 240 && data[1] > 240 && data[2] > 240, "fully transparent pixels should become white");
});

test("normalizeImage caps the longest side without upscaling", async () => {
  const big = await normalizeImage(await makeJpeg(4000, 3000));
  const meta = await sharp(big.buffer).metadata();
  assert.equal(meta.width, 2048);
  const small = await normalizeImage(await makeJpeg(400, 300));
  assert.equal((await sharp(small.buffer).metadata()).width, 400);
});

test("normalizeImage rejects undersized and corrupt images with user-facing errors", async () => {
  await assert.rejects(normalizeImage(await makeJpeg(60, 60)), (error) => error instanceof ImageError && error.userFacing);
  await assert.rejects(normalizeImage(Buffer.from("definitely not an image")), ImageError);
});

test("inspectGlb reports mesh statistics", () => {
  const stats = inspectGlb(makeGlb());
  assert.equal(stats.triangles, 1);
  assert.equal(stats.vertices, 3);
  assert.equal(stats.materials, 1);
  assert.equal(stats.skins, 0);
  assert.equal(stats.animations, 0);
});

test("inspectGlb measures the bounding box and flags paper-thin cut-outs", () => {
  const chubby = inspectGlb(makeGlb([0.35, 0.305, 0.122]));
  assert.deepEqual(chubby.size, { x: 0.35, y: 0.305, z: 0.122 });
  assert.equal(chubby.flat, false);

  // The real flat-sticker result measured 0.08 x 0.07 x 0.002 m.
  const sheet = inspectGlb(makeGlb([0.08, 0.07, 0.002]));
  assert.equal(sheet.flat, true);

  // The real python-logo result measured 0.12 x 0.028 x 0.002 m: long and thin, but still a sheet.
  assert.equal(inspectGlb(makeGlb([0.12, 0.028, 0.002])).flat, true);
  // A genuinely slim object (a pencil) must not be flagged: two small axes, one large.
  assert.equal(inspectGlb(makeGlb([0.2, 0.01, 0.01])).flat, false);
});

test("inspectGlb rejects truncated or non-GLB data", () => {
  assert.equal(isGlb(Buffer.from("nope")), false);
  assert.throws(() => inspectGlb(Buffer.from("nope")));
  assert.throws(() => inspectGlb(makeGlb().subarray(0, 30)), /截断/);
});

test("renameGlbAnimations restores preset names and keeps the mesh untouched", () => {
  const before = makeAnimatedGlb(["animation_1", "animation_2"]);
  const after = renameGlbAnimations(before, ["Idle_Loop", "Walk_Loop"]);
  assert.deepEqual(inspectGlb(after).animationNames, ["Idle_Loop", "Walk_Loop"]);
  assert.equal(inspectGlb(after).triangles, inspectGlb(before).triangles);
  assert.equal(after.length % 4, 0, "GLB chunks must stay 4-byte aligned");

  // A count mismatch must leave the file alone rather than mislabel clips.
  assert.equal(renameGlbAnimations(before, ["Idle_Loop"]), before);
  assert.equal(renameGlbAnimations(Buffer.from("not a glb"), ["Idle_Loop"]).toString(), "not a glb");
});
