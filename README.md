# Animo 灵动萌

把现实里的小物、或网上看到的小家伙（奶蛙、大肥鱼这类角色图），变成能站在真实桌面上陪你的 AR 萌宠。

3D 资产由 **Aholo Lux3D** 生成，密钥只放在后端。手机端只保留原生 Android 客户端（`android/`）。

---

## 1. 客户端

唯一客户端在 `android/`：ARCore 原生负责相机、平面检测与锚点，Filament（gltfio）直接加载 GLB，UI 用 Jetpack Compose，Gradle 构建，arm64 包体约 24 MB。图鉴 / 四种来源 / 孵化 / AR 放置均已在真机验证。

---

## 2. 能做什么

**四种让它活过来的方式**

| 方式 | 适合 | 默认风格 |
| --- | --- | --- |
| 拍一张 | 现实里的玩偶、杯子、手作 | 原样还原 |
| 相册 | 存下来的表情包、插画、截图 | 立体化（毛绒公仔） |
| 网络图片 | 图片直链，或带 `og:image` 的帖子 | 立体化 |
| 文字描述 | 手边没图，直接描述 | — |

平面角色直接建模会变成「纸片」，背面也无从得知。所以图片输入在服务端走三步：**改写成圆滚滚毛绒公仔 → 生成四个视角 → 四张一起图生 3D**。实测同一张 Q 版角色图：直接建模 8×7×**0.2 cm**（纸片）；三步之后 22.2×25×15.1 cm，五官完整、背面有发夹和裙摆。任何一步失败自动降级。

**进到 AR 之后**：点一下放在桌面上并锚定；默认播放 `Walk_Loop`，摸摸 / 喂食切到
`Idle_Loop`，3 秒后回到走路；挪一挪换个位置；图鉴里随时叫回来（模型缓存在手机本地，
服务端挂了也能用）。

---

## 3. 架构

```
手机（原生）
  ├─ ARCore：相机背景 / 平面检测 / 即时放置 / 锚点
  ├─ Filament（gltfio）：直接加载 GLB，PBR 材质
  ├─ Compose：玫瑰色 HUD，超椭圆连续曲率圆角
  └─ OkHttp + 协程：任务创建、轮询、模型下载、本地图鉴
        │ HTTP
        ▼
后端（Node 20+，Backend/）
  ├─ 收图（SSRF 防护、og:image 提取）→ sharp 规范化
  ├─ 上传 OUS → Lux3D 图生 3D / 文生 3D / 毛绒改写 / 四视角
  ├─ 生成完成后把模型的公开 URL 交给 Lux3D：人形绑骨 → 动作重定向
  ├─ 下载带骨骼动画的 GLB → 存本地（Lux3D 结果链接只活 2 小时）
  └─ 对外只暴露 job 状态；API key 永不出现在 APK

Lux3D 云端（同一套 OpenAPI，无额外服务）
  ├─ /animations/rig/task/create：公开 GLB URL → 人形骨骼
  ├─ /animations/retarget/task/create：Idle_Loop / Walk_Loop / Punch_Jab / Hit_Chest / Death01
  └─ 非人形 / 非 A-Pose / 云端失败 → Node 回退本地 Blender 模板绑骨
```

### 目录

```
android/                       原生客户端（主线）
  app/src/main/java/com/animo/ar/
    MainActivity.kt            入口与页面路由
    AppState.kt                状态机、孵化流程、本地图鉴
    data/                      ApiClient、PetStore、Settings、模型
    ui/                        Shape（超椭圆）、Components、Screens、PlayScreen
Backend/                       Node 服务 + 自动化测试 + 真实链路脚本
tools/                         构建、静默安装、模型探针、应用图标生成
```

---

## 4. 跑起来

### 4.1 后端

```bash
cd Backend
npm install
cp .env.example .env      # 填 LUX3D_API_KEY
npm run dev               # http://127.0.0.1:8787
```

绑骨和动作重定向由 Lux3D 云端接口完成，不需要再启动额外服务；非人形模型自动回退到本地 Blender 模板绑骨。

| 变量 | 说明 |
| --- | --- |
| `LUX3D_API_KEY` | 必填。Aholo 平台 key，**直接放 Authorization，不加 Bearer** |
| `CLIENT_SHARED_SECRET` | 可选，设置后 App 需带同一个密钥 |
| `LUX3D_VERSION` / `LUX3D_FACE_COUNT` | 默认 `G1-Turbo` / 30000 面 |
| `LUX3D_RIG_TIMEOUT_SECONDS` | 默认 480。绑骨 / 动作单个任务的等待上限 |
| `RIG_DISABLED` / `BLENDER_PATH` | `RIG_DISABLED=1` 完全关闭绑骨；`BLENDER_PATH` 指定本地兜底 Blender |
| `MAX_JOBS_PER_IP_PER_DAY` | 默认 30，防刷 |
| `HOST` / `TRUST_PROXY` | 公网部署用；Caddy/Nginx 后设 `TRUST_PROXY=1` |

公网部署（Docker、HTTPS、备案路线）见 [docs/DEPLOY.md](docs/DEPLOY.md)。

### 4.2 原生客户端

```bash
adb reverse tcp:8787 tcp:8787          # 手机访问电脑上的后端

tools/build-android.sh                 # 构建，产物：根目录 Animo-<版本>.apk
tools/install-android.sh               # 构建 + 静默安装 + 启动
```

产物固定放工程根目录，命名 `Animo-<versionName>.apk`，**只保留最新一份**（新构建会清掉旧的）。

发布签名包（连公网后端、玩家可安装）：

```bash
ANIMO_API_BASE_URL=https://api.example.com tools/build-release.sh
```

签名密钥、release 行为与分发步骤见 [docs/DEPLOY.md](docs/DEPLOY.md)。

应用图标：设计稿在 `tools/app-icon/source.jpg`，`tools/make-app-icons.py` 会拟合圆角、去掉
白色四角与白边，按启动器 72dp 视口整幅铺满，生成各密度的 legacy PNG 和自适应图标图层：

```bash
python -I tools/make-app-icons.py              # 重写 res/mipmap-*/ic_launcher*.png
```

> **vivo / OriginOS 用户**：系统的「安装验证」会在每次安装时扫 APK 并要确认，adb 绕不过去。设置 → 安全 → 更多安全设置 → **安装验证** 关掉一次，之后 `tools/install-android.sh` 就能全程自动。

### 4.3 联机对战（双终端）

两台设备都连到同一个后端（各自 `adb reverse tcp:8787 tcp:8787`，或把
`Settings` 里的地址改成电脑的局域网 IP），然后：

1. 设备 A：`对战` → `创建联机房间`，把 4 位房间码给对方
2. 设备 B：`对战` → 输入房间码 → `加入房间`
3. 房主自动共享桌面/发布开战时间，双方同时开打

两端交换各自的精灵 GLB，基于同一个开战时间本地跑**确定性战斗**，所以血量、
出手、KO 完全一致。双方宠物都会出现在两端画面里。

**物理上站到同一张桌子**需要 ARCore Cloud Anchors：

1. 在 Google Cloud Console 启用 **ARCore API**
2. 创建 API key，限制到 Android 应用：包名 `com.animo.ar` + 你的签名 SHA-1
   （`cd android && ./gradlew signingReport` 可查）
3. 把 key 写进 `android/gradle.properties` 或 `~/.gradle/gradle.properties`：

   ```properties
   ARCORE_API_KEY=你的key
   ```

4. 重新构建。房主检测到桌面后会把平面共享成 Cloud Anchor，客机解析后两端精灵
   落在**同一物理位置**。

没有配 key 时联机仍可玩，但双方各用自己设备的桌面（画面布局一致、物理位置不同）。

---

## 5. 已经验证 / 尚未验证

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 后端逻辑（56 项测试） | ✅ | `cd Backend && npm test` |
| SSRF / DNS rebinding 防护 | ✅ | 含真实 socket 测试 |
| 真实 Lux3D 图生 / 文生 3D | ✅ | 58s / 54s 出模，PBR 贴图 |
| 毛绒改写 + 四视角链路 | ✅ | 0.2 cm 纸片 → 22.2×25×15.1 cm |
| 原生 AR：相机画面、平面、放置、锚点 | ✅ | 真机截图，模型立在桌面并有遮挡 |
| 原生端到端：文字 → Lux3D → GLB → AR | ✅ | 真机跑通，全程无人工干预 |
| 云端绑骨 + 动作重定向（直连 Lux3D） | ✅ | Lux3D rig 85 s（52 骨）→ retarget 14 s → 16.4 MB GLB，`skins=1`、`animations=2`（Idle/Walk） |
| 原生图鉴落库与回放 | ✅ | 真机截图，卡片含来源与亲密度 |
| 相机拍照入口 | ✅ | 真机点按可拉起系统相机并正常返回；快门回传未测（vivo 相机屏蔽 adb 注入点击） |
| 删除确认弹窗 | ✅ | 真机截图，标题/正文/取消/删除均正常 |
| 点击宠物本体互动 | ✅ | 真机点击后气泡变化、亲密度 +1 并持久化 |
| 模型朝向修正 | ⚠️ | 已改为面向相机并编译通过，当前环境未检出平面，未能实拍确认 |

失败兜底：后端没 key → 示例宠物；Lux3D 失败 → 显示具体原因回创建页；模型解析失败 → 回退示例并提示。

---

## 6. 已知限制（诚实清单）

- **云端绑骨只吃人形 A-Pose**：Lux3D 的 rig 会拒绝双臂屈肘/贴身的 Q 版角色
  （实测返回 `MODEL_NOT_RIGGABLE`）。毛绒改写与四视角提示词已经要求 A-Pose，
  但非人形宠物（奶蛙、杯子）仍会回退到本地 12 骨模板 + 程序化 idle。
- **动作可能部分成功**：一次请求 5 段动作，Lux3D 若只成功一部分，后端只交付成功
  的片段并按预置名重命名；全部失败才回退本地绑骨。
- **拍照取决于系统相机**：走 `ACTION_IMAGE_CAPTURE`，成像质量由手机相机决定。
- **帖子链接取图依赖 `og:image`**：没有就提示改贴图片直链。
- **模型贴图 2048²**：单只显存约 40 MB，图鉴同时展示多只需要另做缩图。
- **深色原图得到深色公仔**：DeepSeek 鲸鱼这类深品牌色改写后仍偏暗。
- **侧视插画建出来仍是侧视**：2D 是什么视角，3D 就什么朝向。

---

## 7. 下一步

1. 上机验证相机拍照、删除确认、朝向修正
2. 点击宠物本体触发互动（当前只有底部按钮）
3. 图鉴多宠同屏前给贴图加运行时缩图
4. 在真机上确认 Idle/Walk 切换与 A-Pose 云端绑骨的成片效果
5. 给非人形宠物做四足/身体主轴骨架，而不是强行套人形模板

---

## 8. 许可与致谢

- 3D 生成：[Aholo Lux3D](https://labs.aholo3d.cn/api-docs/api-reference)
- 云端绑骨 / 动作重定向：Aholo Lux3D OpenAPI（`/animations/rig`、`/animations/retarget`）
- AR / 渲染：Google ARCore、Filament、SceneView（Apache-2.0 / MIT）
