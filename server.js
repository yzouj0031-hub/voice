import 'dotenv/config';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const {
  PORT = 3000,
  PROVIDER = 'openai',
  BASE_URL = 'https://api.openai.com/v1',
  API_KEY = '',
  MODEL = 'gpt-4o-mini',
  SYSTEM_PROMPT = '你是一个友好、简洁的中文语音助手。回答要口语化、自然，适合朗读出来，不要用 Markdown 符号、列表或表格，尽量简短。',
} = process.env;

const app = express();
app.use(express.json({ limit: '1mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// 前端读取的非敏感配置（不含 API Key）
app.get('/api/config', (req, res) => {
  res.json({ model: MODEL, provider: PROVIDER, configured: Boolean(API_KEY) });
});

/**
 * 聊天接口：接收前端发来的对话历史，转发给上游 AI 接口，
 * 并把上游的流式结果统一成简单的 SSE（data: {"text": "..."}）回传给浏览器。
 * API Key 只在服务器端使用，不会下发到手机。
 */
app.post('/api/chat', async (req, res) => {
  if (!API_KEY) {
    return res.status(500).json({ error: '服务器还没有配置 API_KEY，请在 .env 里填写。' });
  }

  const history = Array.isArray(req.body?.messages) ? req.body.messages : [];
  // 只保留 role 为 user / assistant 的消息，防止注入
  const messages = history
    .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')
    .slice(-20); // 只带最近 20 条，控制上下文长度

  if (messages.length === 0) {
    return res.status(400).json({ error: '没有对话内容。' });
  }

  // 设置 SSE 响应头
  res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
  res.setHeader('Cache-Control', 'no-cache, no-transform');
  res.setHeader('Connection', 'keep-alive');
  res.flushHeaders?.();

  const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);

  // 客户端断开连接时中止上游请求。注意要监听 res 的 close，
  // 而不是 req——req 的 close 在请求体读完时就会触发，会误伤上游请求。
  const controller = new AbortController();
  res.on('close', () => controller.abort());

  try {
    const upstream =
      PROVIDER === 'anthropic'
        ? await callAnthropic(messages, controller.signal)
        : await callOpenAI(messages, controller.signal);

    if (!upstream.ok) {
      const errText = await upstream.text().catch(() => '');
      send({ error: `上游接口返回 ${upstream.status}：${errText.slice(0, 300)}` });
      return res.end();
    }

    const parseChunk = PROVIDER === 'anthropic' ? parseAnthropicSSE : parseOpenAISSE;
    await streamUpstream(upstream, parseChunk, send);

    send({ done: true });
    res.end();
  } catch (err) {
    if (controller.signal.aborted) return; // 客户端主动断开
    send({ error: `请求失败：${err.message}` });
    res.end();
  }
});

// ---- OpenAI 兼容格式 ----
function callOpenAI(messages, signal) {
  return fetch(`${BASE_URL}/chat/completions`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({
      model: MODEL,
      stream: true,
      messages: [{ role: 'system', content: SYSTEM_PROMPT }, ...messages],
    }),
  });
}

function parseOpenAISSE(dataStr) {
  if (dataStr === '[DONE]') return null;
  try {
    const json = JSON.parse(dataStr);
    return json?.choices?.[0]?.delta?.content || '';
  } catch {
    return '';
  }
}

// ---- Anthropic 原生格式 ----
function callAnthropic(messages, signal) {
  return fetch(`${BASE_URL}/v1/messages`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': API_KEY,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 1024,
      stream: true,
      system: SYSTEM_PROMPT,
      messages,
    }),
  });
}

function parseAnthropicSSE(dataStr) {
  try {
    const json = JSON.parse(dataStr);
    if (json.type === 'content_block_delta' && json.delta?.type === 'text_delta') {
      return json.delta.text || '';
    }
  } catch {
    /* 忽略非 JSON 的事件行 */
  }
  return '';
}

// 统一读取上游 SSE 流，逐块解析出文本增量并转发
async function streamUpstream(upstream, parseChunk, send) {
  const reader = upstream.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split('\n');
    buffer = lines.pop() ?? ''; // 最后一行可能不完整，留到下次

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('data:')) continue;
      const dataStr = trimmed.slice(5).trim();
      if (!dataStr) continue;
      const text = parseChunk(dataStr);
      if (text) send({ text });
    }
  }
}

app.listen(PORT, () => {
  console.log(`\n🎙️  语音助手已启动：http://localhost:${PORT}`);
  console.log(`   接口类型: ${PROVIDER}   模型: ${MODEL}   密钥: ${API_KEY ? '已配置' : '❌ 未配置'}\n`);
});
