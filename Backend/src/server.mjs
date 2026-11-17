import { createApp } from "./app.mjs";
import { loadConfig, loadEnvFile } from "./config.mjs";
import { createLux3dClient } from "./lux3d.mjs";
import { createStore } from "./store.mjs";

loadEnvFile();
const config = loadConfig();
const store = createStore(config.dataDir);
await store.init();

const lux3d = config.demoMode ? null : createLux3dClient(config.lux3d);
const app = createApp({ config, store, lux3d });
await app.locals.jobs.restore();

const server = app.listen(config.port, config.host, () => {
  const mode = config.demoMode ? "示例模式（未配置 LUX3D_API_KEY，不会调用 Lux3D）" : "Lux3D 实时生成";
  console.log(`Animo API 已启动 http://${config.host}:${config.port}  模式: ${mode}`);
  if (!config.clientSecret && config.host !== "127.0.0.1") {
    console.warn("警告：服务对外可访问但没有设置 CLIENT_SHARED_SECRET，任何人都可以消耗你的 Lux3D 额度。");
  }
});

// Stop accepting requests, then give in-flight generations a short window to be recorded.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close();
    Promise.race([app.locals.jobs.idle(), new Promise((resolve) => setTimeout(resolve, 5000))]).finally(() => process.exit(0));
  });
}
