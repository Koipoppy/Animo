# 部署与发布

这份文档面向两种情况：**开发者/内测**继续用 debug 包连本机后端；**玩家**只安装签名 release 包，连你们部署好的 HTTPS 后端。玩家不需要仓库、Node，也不需要任何 API key。

---

## 1. 服务器路线与备案

先澄清一个常见误解：

- **App 本身不需要 ICP 备案**；备案约束的是「域名 + 中国大陆服务器」对外提供的网站/接口服务。
- 未备案域名解析到大陆 ECS 时，阿里云会拦截 80/443；非标准端口可临时测试，但不适合正式服务。
- 阿里云香港/新加坡等境外 ECS + 域名**不需要 ICP 备案**，可以直接用 Let's Encrypt。
- 如果上架国内应用商店，还会涉及软件著作权、隐私政策、备案信息等，按各渠道要求准备。

| 路线 | 适用阶段 | 优点 | 代价 |
| --- | --- | --- | --- |
| 大陆 ECS + `IP:8787` + debug 包 | 内测 | 立刻可用，零配置 | HTTP 明文，只能调试包，不适合玩家 |
| 大陆 ECS + 公网 IP + 自签 HTTPS | 无域名、短期验收 | 无需备案，传输加密 | App 需内置证书，换 IP 要重签重打包 |
| 阿里云香港/新加坡 + 未备案域名 + HTTPS | 快速正式上线 | 无需备案，自动 TLS | 大陆玩家延迟略高，需要买域名 |
| 大陆 ECS + 已备案域名 + HTTPS | 长期正式 | 延迟低、合规 | 备案约 1–3 周 |

建议：**内测走第一条，正式走第二条或第三条**。代码层面只改 `ANIMO_API_BASE_URL`，不需要改逻辑。

---

## 2. 用 Docker 部署后端

### 2.1 服务器准备

- Ubuntu 22.04/24.04，开放安全组：`22`，以及 `80`/`443`（走 Caddy）；仅内测时开放 `8787`。
- 安装 Docker 与 compose 插件：

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # 重新登录后生效
```

### 2.2 拉代码与配置

仓库是私有的，服务器上用 deploy key（或把镜像推到阿里云 ACR）：

```bash
git clone git@github.com:Koipoppy/Animo.git
cd Animo/Backend
cp .env.example .env
```

编辑 `Backend/.env`，至少填：

```ini
LUX3D_API_KEY=你的Aholo平台key
MAX_JOBS_PER_IP_PER_DAY=30
```

### 2.3 启动与验证

```bash
docker compose up -d --build
docker compose logs -f api
curl http://127.0.0.1:8787/health
```

`health` 里 `"demoMode": false` 表示 key 已加载。容器默认只把 8787 绑在 `127.0.0.1`，公网访问请走 Caddy。

### 2.4 数据持久化

模型缓存和任务索引都在 `Backend/data`，compose 使用命名卷 `animo_data` 持久化。请定期备份：

```bash
docker run --rm -v animo_data:/data -v "$PWD":/backup alpine \
  tar czf /backup/animo-data-$(date +%F).tgz -C /data .
```

丢了数据不会导致服务不可用，但玩家本地图鉴之外的历史模型需要重新生成。

### 2.5 绑骨能力（重要）

镜像只包含 Node 服务；人形 A-Pose 的绑骨 + Idle/Walk/对战动作由 Lux3D 云端接口直接完成，
不需要额外进程，构建时也不会安装 Python 依赖。

仍未安装 Blender：

- 非人形宠物、或云端绑骨失败时没有本地模板回退，会交付静态模型（无动作）。
- 需要兜底时，可以在镜像里安装 Blender 并设置 `BLENDER_PATH=/usr/bin/blender`。
- 不想用云端绑骨可设 `RIG_DISABLED=1`（会同时关闭本地兜底）。

---

## 3. HTTPS

### 3.1 域名 + Caddy（推荐）

1. 域名 A 记录指向服务器。
2. 安装 Caddy 并启用：

```bash
sudo apt install -y caddy
sudo cp deploy/Caddyfile.example /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile    # 把 api.example.com 换成你的域名
sudo systemctl reload caddy
```

3. 让后端信任反向代理，按真实客户端 IP 限流：

```ini
# Backend/.env
TRUST_PROXY=1
```

```bash
cd Backend && docker compose up -d
curl https://api.example.com/health
```

> 只在 Caddy/Nginx 后面设置 `TRUST_PROXY=1`。如果 8787 直接暴露公网，客户端可以伪造 `X-Forwarded-For` 绕过每日配额。

### 3.2 大陆 ECS 且域名未备案

不要占用 80/443。内测直接：

```ini
# Backend/.env
HOST=0.0.0.0
```

```bash
API_BIND=0.0.0.0 docker compose up -d
```

安全组开放 8787，App 用 **debug 包**连 `http://服务器IP:8787`（debug 允许明文；release 强制 HTTPS）。

### 3.3 无域名：IP + 自签 HTTPS（App 内置信任）

没有域名、也不备案时的正式方案。证书里写服务器 IP，App 内置同一套私有 CA，只信任自己签发的证书。

1. 本地生成证书并嵌入 App（把 IP 换成你的公网 IP）：

```bash
deploy/make-ip-cert.sh 1.2.3.4 8443
```

脚本会生成 `Backend/certs/`、`Backend/Caddyfile.ip`，并把 CA 复制到
`android/app/src/main/res/raw/animo_ca.crt`。

2. 把 `Backend/certs/server.crt`、`Backend/certs/server.key` 和 `Backend/Caddyfile.ip`
   上传到服务器的 `Backend/` 对应位置（`animo-ca.key` 不要上传，离线保存）。

3. 服务器 `Backend/.env` 里加：

```ini
TRUST_PROXY=1
```

4. 启动（8443 为例）：

```bash
cd Backend
HTTPS_PORT=8443 docker compose --profile ip-https up -d --build
```

5. 安全组放行 `8443`（若用 443 则放行 443）。验证：

```bash
curl --cacert Backend/certs/animo-ca.crt https://1.2.3.4:8443/health
```

6. 构建正式 App：

```bash
ANIMO_API_BASE_URL=https://1.2.3.4:8443 tools/build-release.sh
```

注意事项：

- 端口优先试 `443`；被阿里云或现场网络拦截时改用 `8443`。
- 换 IP 或换端口都要重新跑脚本并重打 App。
- 服务器证书 365 天有效，覆盖一个月验收；到期前重签（必要时同时重打 App）。
- `Backend/certs/animo-ca.key` 是私有 CA 私钥，只留在本地/离线，泄露后可被用来伪造服务器。

---

## 4. 构建可分发客户端

### 4.1 生成签名密钥（只做一次）

```bash
keytool -genkeypair -v -keystore android/animo-release.jks \
  -alias animo -keyalg RSA -keysize 4096 -validity 10000

cp android/keystore.properties.example android/keystore.properties
# 编辑 android/keystore.properties，填入密码
```

**务必备份 `.jks` 和密码**。丢失后无法再更新已发布的应用；如果之前已经用别的 key 发布过，必须继续用原 key。

### 4.2 构建 release

```bash
ANIMO_API_BASE_URL=https://api.example.com tools/build-release.sh
```

产物在项目根目录：`Animo-<version>.apk` 和 `.aab`。

release 包的行为：

- 后端地址编译进 APK，默认连 `https://api.example.com`
- 关闭明文流量（`usesCleartextTraffic=false`）
- 隐藏首页「设置」入口，玩家无法改后端地址
- 使用 `android/keystore.properties` 签名

每次发布前记得递增 `android/app/build.gradle.kts` 里的 `versionCode`。需要 Cloud Anchors 时，把 `ARCORE_API_KEY=...` 写进 `android/gradle.properties` 或 `~/.gradle/gradle.properties` 再构建。

### 4.3 内测包与正式包

| | debug | release |
| --- | --- | --- |
| 后端地址 | `http://127.0.0.1:8787`（adb reverse / 局域网） | 构建时注入的 HTTPS 地址 |
| 明文 HTTP | 允许 | 禁止 |
| 连接设置页 | 可见 | 隐藏 |
| 签名 | debug key | 你自己的 release key |

内测可以直接用 `tools/build-android.sh` 产出 debug 包。

---

## 5. 分发

- **侧载/官网**：直接发 APK，玩家需允许「安装未知来源应用」。
- **Google Play**：上传 AAB，准备隐私政策、数据安全表单；`android.hardware.camera.ar` 为必需特性，会限制不支持的设备。
- **国内应用商店**：通常需要软著、隐私政策、备案信息（如使用大陆服务器/域名），按渠道要求准备。

---

## 6. 安全与成本

- `LUX3D_API_KEY` 只存在服务器 `.env`，永远不要打进 APK。
- `CLIENT_SHARED_SECRET` 打进 APK 也能被提取，**不是真正的安全边界**；公开分发建议不设，改用服务端配额。
- 配额：`MAX_JOBS_PER_IP_PER_DAY`、`MAX_QUEUED_JOBS`、`JOB_CONCURRENCY`；建议再在云监控里给 Lux3D 消费设预算告警。
- 8787 不要直接暴露公网；只开 80/443 给 Caddy。
- 定期备份 `Backend/data`，并监控磁盘（模型 10–20 MB/只）。

---

## 7. 上线检查清单

- [ ] `curl https://你的域名/health` 返回 `demoMode: false`
- [ ] Caddy 证书自动续期正常，`TRUST_PROXY=1`
- [ ] `.env` 和 keystore 未被提交，keystore 已备份
- [ ] release 包后端地址正确、设置入口已隐藏
- [ ] 真机走通：创建宠物 → AR 放置 → 图鉴 → 双机对战
- [ ] `versionCode` 已递增
- [ ] 每日配额与消费告警已设置
- [ ] 隐私政策、商店资料准备完毕
