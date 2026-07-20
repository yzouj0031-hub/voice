# 🎙️ 语音助手（像 Siri 一样）

一个手机上用的语音聊天助手：**说话 → 识别成文字 → 发给 AI → 流式回复 → 用语音读出来**。
兼容**所有 AI 接口**：OpenAI、DeepSeek、Kimi、智谱、本地大模型（Ollama / LM Studio）、各种中转，以及原生 Claude。

提供两种形态：

| 形态 | 适合谁 | 怎么用 |
| --- | --- | --- |
| **📱 安卓 App（推荐）** | 想"下载装上就能用" | 下载 APK 安装，打开填接口即可，无需电脑 |
| 网页服务器版 | 会跑 Node 服务的人 | 电脑跑后端，手机浏览器访问 |

---

## 📱 安卓 App（无需电脑，装上就用）

这是一个真正的安卓应用：界面用网页做，**语音识别、语音朗读、联网请求全部用安卓原生能力**，
所以不像普通网页壳那样识别不了语音；直连接口也不受跨域限制。密钥填在 App 的"设置"里、只存在手机本地。

### 拿到 APK
本仓库配了自动打包（GitHub Actions）。每次改动 `android/` 后会在云端编译，产物在两个地方：
1. 仓库 **Releases** → `apk-latest` → 下载 `app-debug.apk`（最方便）
2. 或 **Actions** 里对应那次运行的 Artifacts

> 想自己在电脑上打包：装好 Android Studio，用它打开 `android/` 目录，`Build → Build APK`；
> 或命令行 `cd android && ./gradlew assembleDebug`，产物在 `android/app/build/outputs/apk/debug/`。

### 安装并使用
1. 手机下载 `app-debug.apk`，点开安装。第一次会提示"允许安装未知来源应用"，允许即可。
2. 打开"语音助手"，第一次会让你允许**麦克风**权限。
3. 点右上角 **⚙️** 填：接口类型 / 接口地址 / API 密钥 / 模型名（填法见 App 里的提示或下面的表）。
4. 点麦克风说话，它就会用语音回答你。

### 常见接口填法
| 接口 | 接口类型 | 地址 | 模型 |
| --- | --- | --- | --- |
| DeepSeek | OpenAI 兼容 | `https://api.deepseek.com/v1` | `deepseek-chat` |
| Kimi | OpenAI 兼容 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 智谱 | OpenAI 兼容 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| OpenAI | OpenAI 兼容 | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Claude | Anthropic | `https://api.anthropic.com` | `claude-haiku-4-5` |

> 需要**安卓 6.0 以上**，并且手机装了可用的语音识别服务（多数国行/海外机自带；若提示无识别服务，装一个 Google 应用或系统语音服务即可）。

---

## 💻 网页服务器版（另一种方式）

如果你更想在电脑上跑一个服务、手机用浏览器访问，用这套：
- 语音识别用浏览器自带的 Web Speech API（**安卓 Chrome 效果最好**）
- 后端把你的 API Key 藏在服务器端，手机浏览器拿不到，安全
- 是一个 PWA，可以"添加到主屏幕"

---

## 一、准备

需要 [Node.js](https://nodejs.org) 18 或更高版本，以及一个 AI 接口的 API Key。

```bash
# 1. 安装依赖
npm install

# 2. 生成配置文件
cp .env.example .env
```

然后编辑 `.env`，最少改这三项：

```ini
BASE_URL=https://api.deepseek.com/v1     # 接口地址
API_KEY=sk-你的密钥                        # 你的密钥
MODEL=deepseek-chat                       # 模型名
```

常见接口填法见 `.env.example` 里的注释。用原生 Claude 时把 `PROVIDER` 改成 `anthropic`。

## 二、启动

```bash
npm start
```

看到 `🎙️ 语音助手已启动：http://localhost:3000` 就成功了。电脑浏览器打开这个地址就能先试。

## 三、在手机上用

手机和电脑要连**同一个 Wi-Fi**。

1. 查电脑局域网 IP：Windows 用 `ipconfig`，Mac/Linux 用 `ifconfig` 或 `ip addr`，找形如 `192.168.x.x` 的地址。
2. 手机 **Chrome** 打开 `http://192.168.x.x:3000`。
3. 点麦克风，第一次会问麦克风权限，选允许。
4. Chrome 菜单 → **添加到主屏幕**，之后就能像 App 一样从桌面打开。

> ⚠️ 麦克风在很多浏览器里要求 HTTPS。局域网 IP（`http://192.168.x.x`）在**安卓 Chrome** 上通常可以直接用；如果不行，见下面的"HTTPS / 公网访问"。

## 四、HTTPS / 公网访问（可选）

想在外网用、或遇到麦克风权限问题，最简单是用内网穿透工具生成一个 https 地址：

```bash
# 用 cloudflared（免费，无需账号）
cloudflared tunnel --url http://localhost:3000
# 或用 ngrok
ngrok http 3000
```

把它给你的 `https://xxx.trycloudflare.com` 地址在手机上打开即可。

---

## 常见问题

- **提示"不支持语音识别"**：请用**安卓版 Chrome**。iPhone Safari 不支持网页语音识别。
- **点麦克风没反应**：检查是否允许了麦克风权限；HTTP 下部分浏览器会拦截，改用 HTTPS。
- **不朗读**：安卓需要在点击麦克风的手势里唤醒语音引擎（代码已处理）；确认手机没静音、没开勿扰。
- **回复报错**：检查 `.env` 里的 `BASE_URL` / `API_KEY` / `MODEL` 是否正确，服务器控制台会显示上游返回的错误。

## 结构

```
server.js                 后端：托管网页 + 代理 AI 接口（保护密钥、统一流式）
public/index.html         界面
public/style.css          样式（Siri 风格）
public/app.js             语音识别 + 流式对话 + 朗读
public/manifest.webmanifest / sw.js / icon.svg   PWA（可安装到主屏）
.env.example              配置模板
```
