import sharp from "sharp";

// Identify the real image type from magic bytes instead of trusting client-supplied MIME types.
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export function sniffImage(buffer) {
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return { contentType: "image/jpeg", ext: "jpg" };
  }
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(PNG_SIGNATURE)) {
    return { contentType: "image/png", ext: "png" };
  }
  if (buffer.length >= 12 && buffer.toString("ascii", 0, 4) === "RIFF" && buffer.toString("ascii", 8, 12) === "WEBP") {
    return { contentType: "image/webp", ext: "webp" };
  }
  if (buffer.length >= 6 && /^GIF8[79]a$/.test(buffer.toString("ascii", 0, 6))) {
    return { contentType: "image/gif", ext: "gif" };
  }
  return null;
}

export class ImageError extends Error {
  constructor(message) {
    super(message);
    this.name = "ImageError";
    this.userFacing = true;
  }
}

const MIN_SIDE = 128;
const MAX_SIDE = 2048;
const MAX_PIXELS = 40_000_000;

// Decodes anything sharp understands (incl. GIF/WebP, EXIF-rotated phone photos), flattens
// transparency onto white, caps the longest side, and re-encodes as JPEG. This keeps the
// upload small and gives Lux3D the clean, single-subject input its docs recommend.
export async function normalizeImage(buffer) {
  let pipeline;
  let meta;
  try {
    pipeline = sharp(buffer, { limitInputPixels: MAX_PIXELS, failOn: "error" });
    meta = await pipeline.metadata();
  } catch {
    throw new ImageError("这张图片无法读取，请换一张（JPG、PNG、WebP、GIF 均可）");
  }
  const width = meta.width ?? 0;
  const height = meta.height ?? 0;
  if (Math.min(width, height) < MIN_SIDE) {
    throw new ImageError(`图片太小了（最短边需要至少 ${MIN_SIDE} 像素），请换一张更清晰的`);
  }
  const output = await pipeline
    .rotate()
    .resize({ width: MAX_SIDE, height: MAX_SIDE, fit: "inside", withoutEnlargement: true })
    .flatten({ background: "#ffffff" })
    .jpeg({ quality: 90, mozjpeg: true })
    .toBuffer();
  return { buffer: output, contentType: "image/jpeg", ext: "jpg", width, height };
}
