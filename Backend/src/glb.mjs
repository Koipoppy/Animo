// Minimal GLB inspection: enough to validate a download and report asset statistics.
const GLB_MAGIC = 0x46546c67; // "glTF"
const JSON_CHUNK = 0x4e4f534a; // "JSON"

export function isGlb(buffer) {
  return buffer.length >= 12 && buffer.readUInt32LE(0) === GLB_MAGIC;
}

export function inspectGlb(buffer) {
  if (!isGlb(buffer)) throw new Error("不是有效的 GLB 文件");
  if (buffer.readUInt32LE(8) > buffer.length) throw new Error("GLB 文件被截断");
  if (buffer.length < 20 || buffer.readUInt32LE(16) !== JSON_CHUNK) throw new Error("GLB 缺少 JSON 数据块");
  const jsonLength = buffer.readUInt32LE(12);
  if (20 + jsonLength > buffer.length) throw new Error("GLB JSON 数据块被截断");

  const gltf = JSON.parse(buffer.toString("utf8", 20, 20 + jsonLength));
  const accessors = gltf.accessors ?? [];
  let vertices = 0;
  let triangles = 0;
  for (const mesh of gltf.meshes ?? []) {
    for (const primitive of mesh.primitives ?? []) {
      const position = accessors[primitive.attributes?.POSITION];
      vertices += position?.count ?? 0;
      const indexCount = primitive.indices !== undefined ? accessors[primitive.indices]?.count : position?.count;
      if ((primitive.mode ?? 4) === 4) triangles += Math.floor((indexCount ?? 0) / 3);
    }
  }
  return {
    bytes: buffer.length,
    generator: gltf.asset?.generator ?? null,
    meshes: (gltf.meshes ?? []).length,
    materials: (gltf.materials ?? []).length,
    textures: (gltf.textures ?? []).length,
    skins: (gltf.skins ?? []).length,
    animations: (gltf.animations ?? []).length,
    animationNames: (gltf.animations ?? []).map((animation) => animation.name ?? null),
    bones: (gltf.skins ?? []).reduce((total, skin) => total + (skin.joints?.length ?? 0), 0),
    vertices,
    triangles,
    ...measure(gltf),
  };
}

// Lux3D's combined retarget GLB names its clips animation_1..animation_N; the
// requested preset ids only exist in the task JSON. The Android client plays by
// clip name, so rewrite the JSON chunk and leave the BIN chunk untouched.
export function renameGlbAnimations(buffer, names) {
  if (!Array.isArray(names) || names.length === 0) return buffer;
  try {
    if (!isGlb(buffer) || buffer.readUInt32LE(8) > buffer.length) return buffer;
    const chunks = [];
    let offset = 12;
    while (offset + 8 <= buffer.length) {
      const length = buffer.readUInt32LE(offset);
      const type = buffer.readUInt32LE(offset + 4);
      const body = buffer.subarray(offset + 8, offset + 8 + length);
      if (body.length !== length) return buffer;
      chunks.push({ type, body });
      offset += 8 + length;
    }
    if (chunks.length === 0 || chunks[0].type !== JSON_CHUNK) return buffer;

    const gltf = JSON.parse(chunks[0].body.toString("utf8").trimEnd());
    const animations = gltf.animations ?? [];
    if (animations.length !== names.length) return buffer;
    animations.forEach((animation, index) => {
      animation.name = String(names[index]);
    });

    let json = Buffer.from(JSON.stringify(gltf), "utf8");
    const padding = (4 - (json.length % 4)) % 4;
    if (padding > 0) json = Buffer.concat([json, Buffer.alloc(padding, 0x20)]);

    const parts = [];
    let total = 12;
    parts.push(Buffer.alloc(12));
    chunks.forEach((chunk, index) => {
      const body = index === 0 ? json : chunk.body;
      const header = Buffer.alloc(8);
      header.writeUInt32LE(body.length, 0);
      header.writeUInt32LE(chunk.type, 4);
      parts.push(header, body);
      total += 8 + body.length;
    });
    const result = Buffer.concat(parts, total);
    result.writeUInt32LE(GLB_MAGIC, 0);
    result.writeUInt32LE(2, 4);
    result.writeUInt32LE(total, 8);
    return result;
  } catch {
    return buffer;
  }
}

// A sheet has two large dimensions and one tiny one. Thinnest / middle below this means "paper cut-out".
export const FLAT_RATIO = 0.1;

// Bounding box from POSITION accessor min/max (required by the glTF spec). Node transforms are ignored:
// Lux3D output has none, and this only feeds a hint, never a correctness decision.
function measure(gltf) {
  const accessors = gltf.accessors ?? [];
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  let found = false;
  for (const mesh of gltf.meshes ?? []) {
    for (const primitive of mesh.primitives ?? []) {
      const accessor = accessors[primitive.attributes?.POSITION];
      if (accessor?.min?.length !== 3 || accessor?.max?.length !== 3) continue;
      found = true;
      for (let axis = 0; axis < 3; axis += 1) {
        min[axis] = Math.min(min[axis], accessor.min[axis]);
        max[axis] = Math.max(max[axis], accessor.max[axis]);
      }
    }
  }
  if (!found) return { size: null, flat: false };

  const [x, y, z] = max.map((value, axis) => value - min[axis]);
  const sorted = [x, y, z].sort((a, b) => a - b);
  const round = (value) => Math.round(value * 1000) / 1000;
  return { size: { x: round(x), y: round(y), z: round(z) }, flat: sorted[1] > 0 && sorted[0] / sorted[1] < FLAT_RATIO };
}
