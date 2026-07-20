// ============ 语音助手（安卓 App 内网页逻辑）============
// 语音识别 / 语音合成 / 网络请求都通过 window.Native（原生桥接）完成。

const N = window.Native;

const chatEl = document.getElementById('chat');
const micBtn = document.getElementById('micBtn');
const statusEl = document.getElementById('status');
const clearBtn = document.getElementById('clearBtn');
const settingsBtn = document.getElementById('settingsBtn');
const stopSpeakBtn = document.getElementById('stopSpeakBtn');
const modal = document.getElementById('settings');

const S = {
  provider: document.getElementById('s_provider'),
  baseUrl: document.getElementById('s_baseUrl'),
  apiKey: document.getElementById('s_apiKey'),
  model: document.getElementById('s_model'),
  system: document.getElementById('s_system'),
};

let messages = loadHistory();
let listening = false;
let speaker = null;      // 当前回复的分句朗读器
let assistantBubble = null;
let assistantText = '';

// ---------- 设置 ----------
const DEFAULT_SYSTEM = '你是一个友好、简洁的中文语音助手。回答要口语化、自然，适合朗读出来，不要用 Markdown 符号、列表或表格，尽量简短。';

function loadSettings() {
  return {
    provider: localStorage.getItem('cfg_provider') || 'openai',
    baseUrl: localStorage.getItem('cfg_baseUrl') || '',
    apiKey: localStorage.getItem('cfg_apiKey') || '',
    model: localStorage.getItem('cfg_model') || '',
    system: localStorage.getItem('cfg_system') || DEFAULT_SYSTEM,
  };
}
function openSettings() {
  const c = loadSettings();
  S.provider.value = c.provider;
  S.baseUrl.value = c.baseUrl;
  S.apiKey.value = c.apiKey;
  S.model.value = c.model;
  S.system.value = c.system;
  modal.classList.remove('hidden');
}
function closeSettings() { modal.classList.add('hidden'); }
function saveSettings() {
  localStorage.setItem('cfg_provider', S.provider.value);
  localStorage.setItem('cfg_baseUrl', S.baseUrl.value.trim());
  localStorage.setItem('cfg_apiKey', S.apiKey.value.trim());
  localStorage.setItem('cfg_model', S.model.value.trim());
  localStorage.setItem('cfg_system', S.system.value.trim() || DEFAULT_SYSTEM);
  closeSettings();
  setStatus('设置已保存，点击麦克风说话吧');
}
function isConfigured() {
  const c = loadSettings();
  return c.baseUrl && c.apiKey && c.model;
}

settingsBtn.addEventListener('click', openSettings);
document.getElementById('s_cancel').addEventListener('click', closeSettings);
document.getElementById('s_save').addEventListener('click', saveSettings);
modal.addEventListener('click', (e) => { if (e.target === modal) closeSettings(); });

// 安卓返回键：优先关闭设置弹窗
window.onAndroidBack = function () {
  if (!modal.classList.contains('hidden')) { closeSettings(); return true; }
  return false;
};

// ---------- 麦克风 ----------
micBtn.addEventListener('click', () => {
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  if (!isConfigured()) { setStatus('请先在右上角⚙️设置里填写接口。'); openSettings(); return; }
  N.stopSpeaking();
  if (listening) { N.stopListening(); return; }
  N.startListening();
});

clearBtn.addEventListener('click', () => {
  if (N) N.stopSpeaking();
  messages = [];
  saveHistory();
  chatEl.innerHTML = '';
  showHint();
  setStatus('点击麦克风开始说话');
});

stopSpeakBtn.addEventListener('click', () => { if (N) N.stopSpeaking(); });

// ---------- 原生回调：语音识别 ----------
window.onSpeechStart = function () {
  listening = true;
  micBtn.classList.add('listening');
  setStatus('正在聆听…');
};
window.onSpeechPartial = function (text) {
  if (text) setStatus(text);
};
window.onSpeechResult = function (text) {
  const t = (text || '').trim();
  if (t) sendMessage(t);
};
window.onSpeechEnd = function () {
  listening = false;
  micBtn.classList.remove('listening');
};
window.onSpeechError = function (msg) {
  listening = false;
  micBtn.classList.remove('listening');
  setStatus(msg || '识别出错');
};

// ---------- 原生回调：语音合成 ----------
window.onTtsStart = function () { stopSpeakBtn.classList.remove('hidden'); };
window.onTtsDone = function () { stopSpeakBtn.classList.add('hidden'); };

// ---------- 发送消息 + 接收流式回复 ----------
function sendMessage(text) {
  addBubble('user', text);
  messages.push({ role: 'user', content: text });
  saveHistory();

  micBtn.classList.add('thinking');
  setStatus('思考中…');

  assistantBubble = addBubble('assistant', '');
  assistantBubble.classList.add('speaking');
  assistantText = '';
  speaker = createSentenceSpeaker();

  const c = loadSettings();
  const payload = {
    provider: c.provider,
    baseUrl: c.baseUrl,
    apiKey: c.apiKey,
    model: c.model,
    system: c.system,
    messages: messages.slice(-20),
  };
  N.chat(JSON.stringify(payload));
}

window.onChatDelta = function (text) {
  if (!assistantBubble) return;
  assistantText += text;
  assistantBubble.textContent = assistantText;
  scrollToBottom();
  speaker && speaker.feed(assistantText);
};

window.onChatDone = function () {
  finishChat();
};

window.onChatError = function (msg) {
  if (assistantBubble) {
    assistantBubble.textContent = assistantText
      ? assistantText + '\n\n（' + msg + '）'
      : '出错了：' + msg;
  }
  setStatus(msg || '出错了');
  finishChat(true);
};

function finishChat(isError) {
  micBtn.classList.remove('thinking');
  if (assistantBubble) assistantBubble.classList.remove('speaking');
  if (!isError && speaker) speaker.flush();
  if (!isError && assistantText.trim()) {
    messages.push({ role: 'assistant', content: assistantText });
    saveHistory();
  }
  if (!/思考|聆听|识别|出错|保存/.test(statusEl.textContent)) setStatus('点击麦克风开始说话');
  assistantBubble = null;
  speaker = null;
}

// 边接收边按句子朗读，让回复更即时
function createSentenceSpeaker() {
  let spokenLen = 0;
  let latest = '';
  const boundary = /[。！？!?\n]/;
  return {
    feed(fullText) {
      latest = fullText;
      let rest = fullText.slice(spokenLen);
      let m;
      while ((m = rest.match(boundary))) {
        const idx = m.index + 1;
        const sentence = rest.slice(0, idx).trim();
        if (sentence) N.speak(sentence);
        spokenLen += idx;
        rest = rest.slice(idx);
      }
    },
    flush() {
      const remaining = latest.slice(spokenLen).trim();
      if (remaining) { N.speak(remaining); spokenLen = latest.length; }
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
function setStatus(t) { statusEl.textContent = t; }
function scrollToBottom() { chatEl.scrollTop = chatEl.scrollHeight; }
function showHint() {
  if (document.querySelector('.hint')) return;
  const h = document.createElement('div');
  h.className = 'hint';
  h.textContent = '点击下方麦克风，对我说话吧。我会用语音回答你。\n首次使用请先点右上角 ⚙️ 填写接口。';
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
  catch {}
}

// ---------- 初始化 ----------
(function init() {
  if (messages.length) {
    for (const m of messages) addBubble(m.role, m.content);
  } else {
    showHint();
  }
  if (!isConfigured()) {
    setStatus('首次使用：点右上角 ⚙️ 填写接口');
    openSettings();
  }
})();
