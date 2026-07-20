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
  wake: document.getElementById('s_wake'),
  wakeWord: document.getElementById('s_wakeWord'),
};

let messages = loadHistory();
let listening = false;
let speaker = null;      // 当前回复的分句朗读器
let assistantBubble = null;
let assistantText = '';  // 去掉动作指令后、真正显示/朗读的文本
let assistantRaw = '';   // 模型原始输出（含 <action> 指令），用于结束后解析动作

// ---------- 设置 ----------
const DEFAULT_SYSTEM = '你是一个友好、简洁的中文语音助手。回答要口语化、自然，适合朗读出来，不要用 Markdown 符号、列表或表格，尽量简短。';
const DEFAULT_WAKE_WORD = '你好助手';

function loadSettings() {
  return {
    provider: localStorage.getItem('cfg_provider') || 'openai',
    baseUrl: localStorage.getItem('cfg_baseUrl') || '',
    apiKey: localStorage.getItem('cfg_apiKey') || '',
    model: localStorage.getItem('cfg_model') || '',
    system: localStorage.getItem('cfg_system') || DEFAULT_SYSTEM,
    wake: localStorage.getItem('cfg_wake') === '1',
    wakeWord: localStorage.getItem('cfg_wakeWord') || DEFAULT_WAKE_WORD,
  };
}
function openSettings() {
  const c = loadSettings();
  S.provider.value = c.provider;
  S.baseUrl.value = c.baseUrl;
  S.apiKey.value = c.apiKey;
  S.model.value = c.model;
  S.system.value = c.system;
  S.wake.checked = c.wake;
  S.wakeWord.value = c.wakeWord;
  modal.classList.remove('hidden');
}
function closeSettings() { modal.classList.add('hidden'); }
function saveSettings() {
  localStorage.setItem('cfg_provider', S.provider.value);
  localStorage.setItem('cfg_baseUrl', S.baseUrl.value.trim());
  localStorage.setItem('cfg_apiKey', S.apiKey.value.trim());
  localStorage.setItem('cfg_model', S.model.value.trim());
  localStorage.setItem('cfg_system', S.system.value.trim() || DEFAULT_SYSTEM);
  localStorage.setItem('cfg_wake', S.wake.checked ? '1' : '0');
  localStorage.setItem('cfg_wakeWord', S.wakeWord.value.trim() || DEFAULT_WAKE_WORD);
  closeSettings();
  applyWake();
  setStatus('设置已保存，点击麦克风说话吧');
}

// 根据设置开启/关闭后台语音唤醒
function applyWake() {
  if (!N || !N.setWakeEnabled) return;
  const c = loadSettings();
  if (c.wake) {
    // 需要“显示在其他应用上层”权限，否则被唤醒时无法弹出界面
    if (N.canDrawOverlays && !N.canDrawOverlays()) {
      setStatus('请授予“显示在其他应用上层”权限后返回');
      if (N.openOverlaySettings) N.openOverlaySettings();
    }
    N.setWakeEnabled(true, c.wakeWord);
  } else {
    N.setWakeEnabled(false, '');
  }
}

// 被唤醒后自动开始一轮对话（原生在弹出界面后调用）
window.autoStartConversation = function () {
  if (!isConfigured()) { openSettings(); return; }
  if (!listening) startConversation();
};
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
function startConversation() {
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  N.stopSpeaking();
  N.startListening();
}

micBtn.addEventListener('click', () => {
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  if (!isConfigured()) { setStatus('请先在右上角⚙️设置里填写接口。'); openSettings(); return; }
  if (listening) { N.stopSpeaking(); N.stopListening(); return; }
  startConversation();
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

// ============ 设备操作（像 Siri：打电话 / 订闹钟 / 定时器 / 短信 / 开应用 / 导航 / 搜索 / 日历）============
// 思路：让 AI 在自然语言回复后，追加一段用户看不到也听不到的 <action>{...}</action> 指令；
// 这里把指令从显示/朗读中隐藏，回复结束后解析出来交给原生用安卓 Intent 执行。

// 在用户自己的人设后，追加"设备操作能力"说明和当前时间，让模型知道能做什么、几点了
function buildSystem(userSystem) {
  const base = (userSystem && userSystem.trim()) || DEFAULT_SYSTEM;
  const now = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const nowStr =
    `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ` +
    `${p(now.getHours())}:${p(now.getMinutes())} 星期${'日一二三四五六'[now.getDay()]}`;
  return (
    base +
    '\n\n【设备操作能力】\n' +
    '你运行在用户的安卓手机上，可以帮用户执行下列操作。当用户明确要求做这些事时，' +
    '先用一句自然、简短、口语化的话回应（这句会被朗读出来），然后在回复的最末尾，' +
    '单独追加一个动作指令，格式严格为：<action>{...}</action>。\n' +
    'action 里是一个 JSON 对象，用户看不到也听不到它。需要多个操作时可以连续追加多个 <action>。\n' +
    '如果只是聊天、问答，不要输出 <action>。\n\n' +
    '可用动作：\n' +
    '- 打电话：<action>{"tool":"call","name":"妈妈"}</action> 或 {"tool":"call","number":"10086"}\n' +
    '- 订闹钟：<action>{"tool":"set_alarm","hour":7,"minute":30,"message":"起床"}</action>\n' +
    '- 定时器：<action>{"tool":"set_timer","seconds":600,"message":"煮面"}</action>\n' +
    '- 发短信：<action>{"tool":"send_sms","name":"张三","body":"我晚点到"}</action>（也可用 number）\n' +
    '- 打开应用：<action>{"tool":"open_app","app":"微信"}</action>\n' +
    '- 导航：<action>{"tool":"navigate","destination":"北京南站"}</action>\n' +
    '- 搜索：<action>{"tool":"search_web","query":"明天天气"}</action>\n' +
    '- 加日历：<action>{"tool":"add_calendar","title":"开会","hour":15,"minute":0}</action>\n\n' +
    '规则：hour 用 24 小时制；遇到"半小时后""明早八点"等相对时间，请根据下面的当前时间自己换算成具体的 hour/minute（或 seconds）。\n' +
    `当前时间：${nowStr}`
  );
}

// 从模型输出里去掉 <action> 指令，得到用于显示和朗读的纯文本
// 兼容流式：结尾正在生成、还没闭合的 <action…> 片段也要隐藏，避免把标签读出来
function visibleText(raw) {
  let t = raw.replace(/<action>[\s\S]*?<\/action>/g, ''); // 去掉完整的动作块
  const open = t.indexOf('<action>');
  if (open !== -1) t = t.slice(0, open);                  // 已出现但尚未闭合的动作块
  const partial = t.match(/<[a-z/]*$/i);                  // 结尾处形如 "<"、"<act" 的标签碎片
  if (partial) t = t.slice(0, partial.index);
  return t.replace(/[ \t]+$/, '');
}

// 解析出所有动作对象
function parseActions(raw) {
  const out = [];
  const re = /<action>([\s\S]*?)<\/action>/g;
  let m;
  while ((m = re.exec(raw))) {
    const obj = tryParseJson(m[1].trim());
    if (obj && obj.tool) out.push(obj);
  }
  return out;
}
function tryParseJson(s) {
  try { return JSON.parse(s); } catch {}
  const i = s.indexOf('{');
  const j = s.lastIndexOf('}');
  if (i !== -1 && j > i) { try { return JSON.parse(s.slice(i, j + 1)); } catch {} }
  return null;
}

// 把解析出的动作交给原生执行
function executeActions(raw) {
  if (!N || !N.performActions) return;
  const actions = parseActions(raw);
  if (!actions.length) return;
  try { N.performActions(JSON.stringify(actions)); } catch {}
}

// 原生执行完一个动作后的回调：{ msg, speak }
window.onActionResult = function (payload) {
  let data;
  try { data = JSON.parse(payload); } catch { data = { msg: payload, speak: false }; }
  const msg = (data.msg || '').trim();
  if (!msg) return;
  addBubble('system', msg);
  setStatus(msg);
  // 成功时通常 AI 已经口头确认过，这里不重复朗读；失败/需注意的情况才读出来
  if (data.speak && N && N.speak) N.speak(msg);
};

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
  assistantRaw = '';
  speaker = createSentenceSpeaker();

  const c = loadSettings();
  const payload = {
    provider: c.provider,
    baseUrl: c.baseUrl,
    apiKey: c.apiKey,
    model: c.model,
    system: buildSystem(c.system), // 在用户人设后追加"设备操作能力"说明和当前时间
    messages: messages.slice(-20),
  };
  N.chat(JSON.stringify(payload));
}

window.onChatDelta = function (text) {
  if (!assistantBubble) return;
  assistantRaw += text;
  assistantText = visibleText(assistantRaw); // 隐藏 <action> 指令，只显示/朗读自然语言
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
  // 回复结束后，解析其中的动作指令并交给原生执行（打电话、订闹钟等）
  if (!isError) executeActions(assistantRaw);
  if (!/思考|聆听|识别|出错|保存/.test(statusEl.textContent)) setStatus('点击麦克风开始说话');
  assistantBubble = null;
  speaker = null;
  assistantRaw = '';
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
  h.textContent = '点击下方麦克风，对我说话吧。我会用语音回答你。\n还能帮你打电话、订闹钟、定时器、发短信、开应用、导航和搜索。\n首次使用请先点右上角 ⚙️ 填写接口。';
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
  applyWake(); // 同步后台唤醒状态
})();
