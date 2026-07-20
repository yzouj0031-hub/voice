# 🎙️ 语音助手（像 Siri 一样）

一个手机上用的语音聊天助手：**说话 → 识别成文字 → 发给 AI → 流式回复 → 用语音读出来**。
- 语音识别用浏览器自带的 Web Speech API（**安卓 Chrome 效果最好**）
- 后端把你的 API Key 藏在服务器端，手机浏览器拿不到，安全
- 兼容**所有 AI 接口**：OpenAI、DeepSeek、Kimi、智谱、本地大模型（Ollama / LM Studio）、各种中转，以及原生 Claude
- 是一个 PWA，可以"添加到主屏幕"，用起来跟 App 一样

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
