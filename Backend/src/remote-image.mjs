import dns from "node:dns";
import http from "node:http";
import https from "node:https";
import net from "node:net";
import { Readable } from "node:stream";

export class RemoteImageError extends Error {
  constructor(message, code) {
    super(message);
    this.name = "RemoteImageError";
    this.code = code;
    this.userFacing = true;
  }
}

const MAX_BYTES = 12 * 1024 * 1024;
const MAX_REDIRECTS = 4;
const TIMEOUT_MS = 20_000;
// Some image hosts reject requests that do not look like a browser.
const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

function ipv4Parts(address) {
  return address.split(".").map(Number);
}

// Rejects loopback, private, link-local, CGNAT, multicast, reserved and cloud-metadata ranges.
export function isPublicAddress(address) {
  const family = net.isIP(address);
  if (family === 4) {
    const [a, b] = ipv4Parts(address);
    if (a === 0 || a === 10 || a === 127) return false;
    if (a === 100 && b >= 64 && b <= 127) return false;
    if (a === 169 && b === 254) return false;
    if (a === 172 && b >= 16 && b <= 31) return false;
    if (a === 192 && b === 168) return false;
    if (a === 192 && b === 0) return false;
    if (a === 198 && (b === 18 || b === 19)) return false;
    if (a >= 224) return false;
    return true;
  }
  if (family === 6) {
    const lower = address.toLowerCase();
    if (lower === "::" || lower === "::1") return false;
    const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(lower);
    if (mapped) return isPublicAddress(mapped[1]);
    if (/^f[cd]/.test(lower)) return false; // unique local fc00::/7
    if (/^fe[89ab]/.test(lower)) return false; // link-local fe80::/10
    if (lower.startsWith("ff")) return false; // multicast
    if (lower.startsWith("64:ff9b:")) return false; // NAT64
    return true;
  }
  return false;
}

export function parseRemoteUrl(raw) {
  let url;
  try {
    url = new URL(String(raw).trim());
  } catch {
    throw new RemoteImageError("这不是一个有效的图片链接", "bad_url");
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new RemoteImageError("只支持 http 或 https 图片链接", "bad_scheme");
  }
  if (url.username || url.password) throw new RemoteImageError("链接里不能包含账号密码", "bad_url");
  return url;
}

// Pre-flight check so obviously bad hosts fail fast with a clear error.
async function resolvePublic(hostname, lookupImpl) {
  const bare = hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(bare)) {
    if (!isPublicAddress(bare)) throw new RemoteImageError("不允许访问这个地址", "blocked_host");
    return;
  }
  const records = await new Promise((resolve, reject) => {
    lookupImpl(bare, { all: true }, (error, addresses) => (error ? reject(error) : resolve(addresses)));
  }).catch((error) => {
    throw error instanceof RemoteImageError ? error : new RemoteImageError("无法解析这个图片链接的域名", "dns_failed");
  });
  if (records.length === 0 || records.some((record) => !isPublicAddress(record.address))) {
    throw new RemoteImageError("不允许访问这个地址", "blocked_host");
  }
}

// The authoritative check. The socket connects to exactly the address validated here, so a DNS
// server that answers "public" for the pre-flight lookup and "private" for the connect (rebinding)
// cannot slip through.
export function createSafeLookup(lookupImpl = dns.lookup) {
  return (hostname, options, callback) => {
    lookupImpl(hostname, { ...options, all: true }, (error, addresses) => {
      if (error) return callback(error);
      const list = Array.isArray(addresses) ? addresses : [];
      if (list.length === 0 || list.some((record) => !isPublicAddress(record.address))) {
        return callback(new RemoteImageError("不允许访问这个地址", "blocked_host"));
      }
      return options?.all ? callback(null, list) : callback(null, list[0].address, list[0].family);
    });
  };
}

const NULL_BODY_STATUSES = new Set([101, 204, 205, 304]);

// fetch-compatible wrapper over node:http(s) so we can control name resolution per connection.
export function createGuardedFetch({ lookup = createSafeLookup() } = {}) {
  return (url, { headers = {}, signal } = {}) =>
    new Promise((resolve, reject) => {
      const transport = url.protocol === "https:" ? https : http;
      const request = transport.request(url, { method: "GET", headers, lookup, signal }, (res) => {
        const responseHeaders = new Headers();
        for (const [key, value] of Object.entries(res.headers)) {
          if (value !== undefined) responseHeaders.set(key, Array.isArray(value) ? value.join(", ") : value);
        }
        const status = res.statusCode ?? 502;
        if (NULL_BODY_STATUSES.has(status)) res.resume();
        resolve(new Response(NULL_BODY_STATUSES.has(status) ? null : Readable.toWeb(res), { status, headers: responseHeaders }));
      });
      request.on("error", reject);
      request.end();
    });
}

// HTML pages are only inspected for their preview image, so a hard cap keeps that cheap.
async function readTextLimited(response, maxBytes = 512 * 1024) {
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    chunks.push(value);
    if (total >= maxBytes) {
      await reader.cancel();
      break;
    }
  }
  return Buffer.concat(chunks).toString("utf8");
}

function metaContent(html, names) {
  for (const name of names) {
    // Attribute order varies between sites, so match both orders and either quote style.
    const patterns = [
      new RegExp(`<meta[^>]+(?:property|name)=["']${name}["'][^>]*content=["']([^"']+)["']`, "i"),
      new RegExp(`<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']${name}["']`, "i"),
    ];
    for (const pattern of patterns) {
      const match = pattern.exec(html);
      if (match?.[1]) return match[1].trim();
    }
  }
  return null;
}

// Picks the page's representative picture: og:image, then Twitter's, then a <link rel="image_src">.
export function extractPageImage(html, pageUrl) {
  const raw =
    metaContent(html, ["og:image", "og:image:url", "og:image:secure_url"]) ??
    metaContent(html, ["twitter:image", "twitter:image:src"]) ??
    /<link[^>]+rel=["']image_src["'][^>]*href=["']([^"']+)["']/i.exec(html)?.[1] ??
    null;
  if (!raw) return null;
  try {
    // Resolve relative URLs against the page; parseRemoteUrl rejects anything that isn't http(s).
    return parseRemoteUrl(new URL(raw, pageUrl).toString());
  } catch {
    return null;
  }
}

async function readLimited(response) {  const declared = Number(response.headers.get("content-length") ?? 0);
  if (declared > MAX_BYTES) throw new RemoteImageError("图片太大了（超过 12MB）", "too_large");
  const reader = response.body?.getReader();
  if (!reader) throw new RemoteImageError("无法读取图片内容", "empty");
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > MAX_BYTES) {
      await reader.cancel();
      throw new RemoteImageError("图片太大了（超过 12MB）", "too_large");
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks);
}

// Downloads an image from a user-supplied URL. Every hop (including redirects) is re-validated
// so a public URL cannot bounce the server into the internal network.
export async function fetchRemoteImage(
  rawUrl,
  { fetchImpl = createGuardedFetch(), lookupImpl = dns.lookup, allowPageImage = true } = {},
) {
  let url = parseRemoteUrl(rawUrl);
  for (let hop = 0; hop <= MAX_REDIRECTS; hop += 1) {
    await resolvePublic(url.hostname, lookupImpl);
    let response;
    try {
      response = await fetchImpl(url, {
        headers: { "User-Agent": BROWSER_UA, Accept: "image/*,*/*;q=0.5" },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch (error) {
      if (error instanceof RemoteImageError) throw error;
      if (error?.cause instanceof RemoteImageError) throw error.cause;
      throw new RemoteImageError("下载图片失败，请检查链接是否能直接打开", "fetch_failed");
    }
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("location");
      if (!location) throw new RemoteImageError("图片链接跳转异常", "bad_redirect");
      url = parseRemoteUrl(new URL(location, url).toString());
      continue;
    }
    if (!response.ok) {
      throw new RemoteImageError(`下载图片失败（对方服务器返回 ${response.status}）`, "http_error");
    }
    const type = String(response.headers.get("content-type") ?? "").toLowerCase();
    if (type.includes("text/html")) {
      // Meme and mascot pictures are usually shared as post links, not raw image URLs, so look for the
      // page's own preview image before giving up. Depth is limited so this can never become a crawl.
      if (hop === 0 && allowPageImage) {
        const candidate = extractPageImage(await readTextLimited(response), url);
        if (candidate) {
          url = candidate;
          allowPageImage = false;
          continue;
        }
      }
      throw new RemoteImageError("这是一个网页，没有找到可用的图片。请打开页面后长按图片 → 复制图片地址", "not_image");
    }
    return readLimited(response);
  }
  throw new RemoteImageError("图片链接跳转次数过多", "too_many_redirects");
}
