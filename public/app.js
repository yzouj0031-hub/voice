// ================= 语音助手前端逻辑 =================

const chatEl = document.getElementById('chat');
const micBtn = document.getElementById('micBtn');
const statusEl = document.getElementById('status');
const clearBtn = document.getElementById('clearBtn');
const stopSpeakBtn = document.getElementById('stopSpeakBtn');

// 对话历史（发给后端），并持久化到本地
let messages = loadHistory();

// ---------- 语音识别（浏览器自带） ----------
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
let recognition = null;
let listening = false;

if (SR) {
  recognition = new SR();
  recognition.lang = 'zh-CN';
  recognition.interimResults = true;
  recognition.continuous = false;
  recognition.maxAlternatives = 1;

  let finalText = '';

  recognition.onstart = () => {
    listening = true;
    finalText = '';
    micBtn.classList.add('listening');
    setStatus('正在聆听…');
  };

  recognition.onresult = (event) => {
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const r = event.results[i];
      if (r.isFinal) finalText += r[0].transcript;
      else interim += r[0].transcript;
    }
    setStatus((finalText + interim) || '正在聆听…');
  };

  recognition.onerror = (e) => {
    listening = false;
    micBtn.classList.remove('listening');
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      setStatus('没有麦克风权限，请在浏览器里允许麦克风。');
    } else if (e.error === 'no-speech') {
      setStatus('没听清，请再点一次麦克风。');
    } else {
      setStatus('识别出错：' + e.error);
    }
  };

  recognition.onend = () => {
    listening = false;
    micBtn.classList.remove('listening');
    const text = finalText.trim();
    if (text) {
      sendMessage(text);
    } else {
      setStatus('点击麦克风开始说话');
    }
  };
} else {
  setStatus('当前浏览器不支持语音识别，请用安卓版 Chrome 打开。');
}

// ---------- 麦克风按钮 ----------
micBtn.addEventListener('click', () => {
  if (!recognition) return;
  // 说话前先停掉正在进行的朗读
  stopSpeaking();
  if (listening) {
    recognition.stop();
    return;
  }
  // 用户手势里“唤醒”一下语音合成引擎（安卓 Chrome 需要）
  primeSpeech();
  try {
    recognition.start();
  } catch {
    // 连续点击可能抛错，忽略
  }
});

clearBtn.addEventListener('click', () => {
  stopSpeaking();
  messages = [];
  saveHistory();
  chatEl.innerHTML = '';
  showHint();
  setStatus('点击麦克风开始说话');
});

stopSpeakBtn.addEventListener('click', stopSpeaking);

// ---------- 发送消息并接收流式回复 ----------
async function sendMessage(text) {
  addBubble('user', text);
  messages.push({ role: 'user', content: text });
  saveHistory();

  micBtn.classList.add('thinking');
  setStatus('思考中…');

  const bubble = addBubble('assistant', '');
  bubble.classList.add('speaking');
  let full = '';
  const speaker = createSentenceSpeaker(); // 边收边读

  try {
    const resp = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages }),
    });

    if (!resp.ok || !resp.body) {
      const info = await resp.json().catch(() => ({}));
      throw new Error(info.error || `服务器返回 ${resp.status}`);
    }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';

      for (const line of lines) {
        const t = line.trim();
        if (!t.startsWith('data:')) continue;
        const payload = JSON.parse(t.slice(5).trim());
        if (payload.error) throw new Error(payload.error);
        if (payload.text) {
          full += payload.text;
          bubble.textContent = full;
          scrollToBottom();
          speaker.feed(full);
        }
        if (payload.done) speaker.flush();
      }
    }
  } catch (err) {
    full = full || '';
    bubble.textContent = full ? full + '\n\n（' + err.message + '）' : '出错了：' + err.message;
    setStatus(err.message);
  } finally {
    micBtn.classList.remove('thinking');
    bubble.classList.remove('speaking');
    if (full.trim()) {
      messages.push({ role: 'assistant', content: full });
      saveHistory();
    }
    if (!/思考|聆听|识别/.test(statusEl.textContent)) setStatus('点击麦克风开始说话');
  }
}

// ---------- 语音合成（朗读） ----------
let voiceReady = false;
function primeSpeech() {
  if (voiceReady || !('speechSynthesis' in window)) return;
  // 触发一次静默播放，解锁引擎
  const u = new SpeechSynthesisUtterance('');
  window.speechSynthesis.speak(u);
  voiceReady = true;
}

function pickChineseVoice() {
  const voices = window.speechSynthesis?.getVoices?.() || [];
  return (
    voices.find((v) => /zh/i.test(v.lang)) ||
    voices.find((v) => /Chinese|中文/i.test(v.name)) ||
    null
  );
}

function speak(text) {
  if (!('speechSynthesis' in window) || !text.trim()) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'zh-CN';
  const v = pickChineseVoice();
  if (v) u.voice = v;
  u.rate = 1.05;
  u.onstart = () => stopSpeakBtn.classList.remove('hidden');
  u.onend = () => {
    if (!window.speechSynthesis.speaking) stopSpeakBtn.classList.add('hidden');
  };
  window.speechSynthesis.speak(u);
}

function stopSpeaking() {
  if ('speechSynthesis' in window) window.speechSynthesis.cancel();
  stopSpeakBtn.classList.add('hidden');
}

// 边接收边按句子朗读，让回复更即时
function createSentenceSpeaker() {
  let spokenLen = 0;      // 已经朗读到完整文本的哪个位置
  let latest = '';        // 记录最新的完整文本
  const boundary = /[。！？!?\n]/;
  return {
    feed(fullText) {
      latest = fullText;
      let rest = fullText.slice(spokenLen);
      let match;
      while ((match = rest.match(boundary))) {
        const idx = match.index + 1;
        const sentence = rest.slice(0, idx).trim();
        if (sentence) speak(sentence);
        spokenLen += idx;
        rest = rest.slice(idx);
      }
    },
    flush() {
      // 朗读剩余不足一句的尾巴
      const remaining = latest.slice(spokenLen).trim();
      if (remaining) {
        speak(remaining);
        spokenLen = latest.length;
      }
    },
  };
}

// ---------- 界面辅助 ----------
function addBubble(role, text) {
  hideHint();
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  div.textContent = text;
  chatEl.appendChild(div);
  scrollToBottom();
  return div;
}

function setStatus(text) { statusEl.textContent = text; }
function scrollToBottom() { chatEl.scrollTop = chatEl.scrollHeight; }

function showHint() {
  if (document.querySelector('.hint')) return;
  const h = document.createElement('div');
  h.className = 'hint';
  h.textContent = '点击下方麦克风，对我说话吧。我会用语音回答你。';
  chatEl.appendChild(h);
}
function hideHint() { document.querySelector('.hint')?.remove(); }

// ---------- 历史持久化 ----------
function loadHistory() {
  try { return JSON.parse(localStorage.getItem('voice_history') || '[]'); }
  catch { return []; }
}
function saveHistory() {
  try { localStorage.setItem('voice_history', JSON.stringify(messages.slice(-40))); }
  catch { /* 忽略 */ }
}

// ---------- 初始化 ----------
function init() {
  if (messages.length) {
    for (const m of messages) addBubble(m.role, m.content);
  } else {
    showHint();
  }
  // 预加载语音列表
  if ('speechSynthesis' in window) window.speechSynthesis.getVoices();
}
init();

// 注册 Service Worker（可安装到手机主屏）
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
