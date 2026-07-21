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
  voiceMode: document.getElementById('s_voiceMode'),
  wake: document.getElementById('s_wake'),
  wakeWord: document.getElementById('s_wakeWord'),
};
const fetchModelsBtn = document.getElementById('s_fetchModels');
const testConnBtn = document.getElementById('s_testConn');
const diagnoseBtn = document.getElementById('s_diagnose');
const modelList = document.getElementById('modelList');
const modelChips = document.getElementById('s_modelChips');
const testResult = document.getElementById('s_testResult');
const textInput = document.getElementById('textInput');
const sendTextBtn = document.getElementById('sendTextBtn');

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
    voiceMode: localStorage.getItem('cfg_voiceMode') || 'system',
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
  S.voiceMode.value = c.voiceMode;
  S.wake.checked = c.wake;
  S.wakeWord.value = c.wakeWord;
  // 每次打开清掉上次的模型列表和测试结果
  modelChips.classList.add('hidden');
  modelChips.innerHTML = '';
  testResult.classList.add('hidden');
  modelList.innerHTML = '';
  modal.classList.remove('hidden');
}
function closeSettings() {
  if (wakeTesting && N && N.stopWakeTest) { N.stopWakeTest(); wakeTesting = false; if (wakeTestBtn) wakeTestBtn.textContent = '🎤 测试唤醒识别'; }
  modal.classList.add('hidden');
}
function saveSettings() {
  localStorage.setItem('cfg_provider', S.provider.value);
  localStorage.setItem('cfg_baseUrl', S.baseUrl.value.trim());
  localStorage.setItem('cfg_apiKey', S.apiKey.value.trim());
  localStorage.setItem('cfg_model', S.model.value.trim());
  localStorage.setItem('cfg_system', S.system.value.trim() || DEFAULT_SYSTEM);
  localStorage.setItem('cfg_voiceMode', S.voiceMode.value);
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

// ---------- 拉取模型 / 测试连接 ----------
// 用设置框里"当前正在编辑"的值（不是已保存的），方便填完立刻验证
function currentInput() {
  return {
    provider: S.provider.value,
    baseUrl: S.baseUrl.value.trim(),
    apiKey: S.apiKey.value.trim(),
    model: S.model.value.trim(),
  };
}
function showTestResult(msg, kind) {
  testResult.textContent = msg;
  testResult.className = 'test-result' + (kind ? ' ' + kind : '');
}

fetchModelsBtn.addEventListener('click', () => {
  if (!N || !N.listModels) { showTestResult('请在语音助手 App 内使用。', 'err'); return; }
  const c = currentInput();
  if (!c.baseUrl || !c.apiKey) { showTestResult('请先填写接口地址和密钥。', 'err'); return; }
  fetchModelsBtn.disabled = true;
  fetchModelsBtn.textContent = '拉取中…';
  showTestResult('正在拉取模型列表…', '');
  N.listModels(JSON.stringify(c));
});

testConnBtn.addEventListener('click', () => {
  if (!N || !N.testConnection) { showTestResult('请在语音助手 App 内使用。', 'err'); return; }
  const c = currentInput();
  if (!c.baseUrl || !c.apiKey || !c.model) { showTestResult('请先填写接口地址、密钥和模型。', 'err'); return; }
  testConnBtn.disabled = true;
  testConnBtn.textContent = '测试中…';
  showTestResult('正在测试连接…', '');
  N.testConnection(JSON.stringify(c));
});

// 快速填入：一点就自动填好接口类型/地址/模型，用户只需再粘贴密钥
document.querySelectorAll('.preset').forEach((b) => {
  b.addEventListener('click', () => {
    S.provider.value = b.dataset.provider;
    S.baseUrl.value = b.dataset.url;
    S.model.value = b.dataset.model;
    document.querySelectorAll('.preset').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    showTestResult('已填好「' + b.textContent + '」的地址和模型，现在只要把上面的 API 密钥粘贴进去就行。', 'ok');
  });
});

// 自检：一眼看清麦克风 / 语音识别 / 朗读 / 接口 哪个是好的
diagnoseBtn.addEventListener('click', () => {
  if (!N || !N.diagnostics) { showTestResult('请在语音助手 App 内使用自检。', 'err'); return; }
  let d;
  try { d = JSON.parse(N.diagnostics()); } catch { d = {}; }
  const c = currentInput();
  const configured = !!(c.baseUrl && c.apiKey && c.model);
  const yn = (b) => (b ? '✓ 正常' : '✗ 不可用');
  const lines = [
    '麦克风权限：' + yn(d.mic),
    '语音识别服务：' + yn(d.recognitionAvailable),
    '朗读引擎：' + yn(d.ttsReady),
    '接口已填写：' + yn(configured),
  ];
  let tail = '';
  if (!d.recognitionAvailable)
    tail = '\n→ 这台手机没有可用的语音识别服务。✅ 最简单的解决办法：把上面的「语音输入方式」切换成「☁️ 云端识别」并保存——App 会自己录音发给 AI 听，完全不依赖手机的语音服务（需模型支持听音频，如 Gemini）。也可以用下方键盘打字聊天。';
  else if (!d.mic) tail = '\n→ 请到系统设置里给本应用允许“麦克风”权限。';
  else if (!configured) tail = '\n→ 请先在上面填好接口地址/密钥/模型并保存。';
  else if (!d.ttsReady) tail = '\n→ 朗读引擎还没就绪，可稍等或在系统里安装中文 TTS 语音。';
  const allOk = d.mic && d.recognitionAvailable && d.ttsReady && configured;
  showTestResult(lines.join('\n') + tail, allOk ? 'ok' : 'err');
});

// 原生回调：拉到的模型列表（字符串数组）
window.onModelsResult = function (payload) {
  fetchModelsBtn.disabled = false;
  fetchModelsBtn.textContent = '↧ 拉取模型';
  let ids;
  try { ids = JSON.parse(payload); } catch { ids = []; }
  if (!Array.isArray(ids) || ids.length === 0) {
    showTestResult('没拿到模型列表，请手动填写模型名。', 'err');
    return;
  }
  // 填充输入框的自动补全
  modelList.innerHTML = '';
  ids.forEach((id) => {
    const opt = document.createElement('option');
    opt.value = id;
    modelList.appendChild(opt);
  });
  // 渲染成可点的小标签，点一下就填入模型名
  modelChips.innerHTML = '';
  ids.forEach((id) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = id;
    chip.addEventListener('click', () => {
      S.model.value = id;
      [...modelChips.children].forEach((c) => c.classList.remove('active'));
      chip.classList.add('active');
    });
    modelChips.appendChild(chip);
  });
  modelChips.classList.remove('hidden');
  showTestResult(`拉到 ${ids.length} 个模型，点一个填入，或直接在上面输入。`, 'ok');
};

window.onModelsError = function (msg) {
  fetchModelsBtn.disabled = false;
  fetchModelsBtn.textContent = '↧ 拉取模型';
  showTestResult(msg || '拉取失败。', 'err');
};

// 原生回调：测试连接结果 { ok, msg }
window.onTestResult = function (payload) {
  testConnBtn.disabled = false;
  testConnBtn.textContent = '✓ 测试连接';
  let data;
  try { data = JSON.parse(payload); } catch { data = { ok: false, msg: payload }; }
  showTestResult(data.msg || (data.ok ? '连接正常。' : '连接失败。'), data.ok ? 'ok' : 'err');
};

// 安卓返回键：优先关闭设置弹窗
window.onAndroidBack = function () {
  if (!modal.classList.contains('hidden')) { closeSettings(); return true; }
  return false;
};

// ---------- 麦克风 ----------
let cloudRecording = false; // 云端识别模式：是否正在录音

function startConversation() {
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  N.stopSpeaking();
  if (loadSettings().voiceMode === 'cloud') {
    N.startCloudRecording && N.startCloudRecording();
  } else {
    N.startListening();
  }
}

micBtn.addEventListener('click', () => {
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  if (!isConfigured()) { setStatus('请先在右上角⚙️设置里填写接口。'); openSettings(); return; }
  if (loadSettings().voiceMode === 'cloud') {
    // 云端识别：第一下开始录音，第二下停止并发送
    if (cloudRecording) { N.stopCloudRecording(); return; }
    startConversation();
    return;
  }
  if (listening) { N.stopSpeaking(); N.stopListening(); return; }
  startConversation();
});

// ---------- 原生回调：云端识别录音 ----------
window.onRecordStart = function () {
  cloudRecording = true;
  micBtn.classList.add('listening');
  setStatus('正在录音…说完再点一下麦克风发送');
};
window.onRecordCancel = function () {
  cloudRecording = false;
  micBtn.classList.remove('listening');
  setStatus('已取消');
};
window.onRecordError = function (msg) {
  cloudRecording = false;
  micBtn.classList.remove('listening');
  showMaybePermissionError(msg || '录音失败');
};
window.onCloudAudio = function (b64) {
  cloudRecording = false;
  micBtn.classList.remove('listening');
  sendAudioMessage(b64);
};

// 键盘输入兜底：语音识别用不了时，打字也能对话，回答照样语音朗读
function sendTyped() {
  const t = textInput.value.trim();
  if (!t) return;
  if (!N) { setStatus('请在语音助手 App 内打开。'); return; }
  if (!isConfigured()) { setStatus('请先在右上角⚙️设置里填写接口。'); openSettings(); return; }
  N.stopSpeaking && N.stopSpeaking();
  textInput.value = '';
  sendMessage(t);
}
sendTextBtn.addEventListener('click', sendTyped);
textInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); sendTyped(); }
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
  showMaybePermissionError(msg || '识别出错');
};

// 授予麦克风权限后自动开始；被拒绝时更新横幅并引导去设置
window.onMicGranted = function () {
  micDeniedPermanently = false;
  hideMicBanner();
  setStatus('已获得麦克风权限，开始…');
  startConversation();
};
window.onMicDenied = function (permanent) {
  micDeniedPermanently = permanent === '1';
  refreshMicBanner(false);
  setStatus(micDeniedPermanently ? '麦克风被禁止，点上方横幅去设置开启' : '需要麦克风权限');
};

// 错误里若涉及"权限"，让状态栏可点，一键跳系统设置
function showMaybePermissionError(msg) {
  if (/权限/.test(msg)) setStatus(msg + '（点此去开启 ▸）', goOpenAppSettings);
  else setStatus(msg);
}

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
    '- 加日历：<action>{"tool":"add_calendar","title":"开会","hour":15,"minute":0}</action>\n' +
    '- 调音量：<action>{"tool":"set_volume","level":50}</action>（level 0~100），或 {"tool":"set_volume","action":"up"}（action 可为 up/down/mute）\n' +
    '- 调亮度：<action>{"tool":"set_brightness","level":80}</action>（level 0~100）\n' +
    '- 手电筒：<action>{"tool":"flashlight","on":true}</action>（关闭用 "on":false）\n\n' +
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

// ---------- 云端识别：把录音直接发给 AI，让它"听"完转写并回答 ----------
let audioTurn = false;        // 本轮是语音音频输入
let audioUserBubble = null;   // 用户气泡（先显示占位，收到转写后替换）
let audioTranscript = '';     // 模型转写出的用户原话

function sendAudioMessage(b64) {
  const c = loadSettings();
  if (c.provider === 'anthropic') {
    setStatus('云端识别暂不支持 Anthropic 接口，请换 OpenAI 兼容接口（如 Gemini），或改用系统识别。');
    return;
  }
  audioTurn = true;
  audioTranscript = '';
  audioUserBubble = addBubble('user', '🎤 语音消息（识别中…）');

  micBtn.classList.add('thinking');
  setStatus('识别中…');

  assistantBubble = addBubble('assistant', '');
  assistantBubble.classList.add('speaking');
  assistantText = '';
  assistantRaw = '';
  speaker = createSentenceSpeaker();

  // 在设备操作说明之外，追加"语音输入"规则：先转写一行，再正常回答
  const sys =
    buildSystem(c.system) +
    '\n\n【语音输入】本条用户消息是一段语音音频。请先把用户说的话逐字转写出来，' +
    '作为回复的第一行单独输出，格式严格为：【你说：转写内容】。' +
    '然后从第二行开始，按上面的规则正常回答用户。不要跳过转写行。';

  // 历史里只带文字（音频不重复上传），本轮追加音频消息
  const req = messages.slice(-19).map((m) => ({ role: m.role, content: m.content }));
  req.push({
    role: 'user',
    content: [{ type: 'input_audio', input_audio: { data: b64, format: 'wav' } }],
  });

  N.chat(JSON.stringify({
    provider: c.provider,
    baseUrl: c.baseUrl,
    apiKey: c.apiKey,
    model: c.model,
    system: sys,
    messages: req,
  }));
}

// 从"【你说：...】\n回答"里拆出转写和正文；标记未完成时先都不显示
function splitAudioReply(raw) {
  const m = raw.match(/^\s*【你说：([\s\S]*?)】\s*\n?/);
  if (m) return { transcript: m[1].trim(), body: raw.slice(m[0].length) };
  if (/^\s*【[^】]*$/.test(raw)) return { transcript: null, body: '' }; // 标记生成中
  return { transcript: null, body: raw }; // 模型没按格式来，整段当回答
}

window.onChatDelta = function (text) {
  if (!assistantBubble) return;
  assistantRaw += text;
  let shown = assistantRaw;
  if (audioTurn) {
    const s = splitAudioReply(assistantRaw);
    if (s.transcript && !audioTranscript) {
      audioTranscript = s.transcript;
      if (audioUserBubble) audioUserBubble.textContent = s.transcript; // 用户气泡换成他说的话
      setStatus('思考中…');
    }
    shown = s.body;
  }
  assistantText = visibleText(shown); // 隐藏 <action> 指令，只显示/朗读自然语言
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
  // 语音输入轮：把"用户说的话"（转写）写进历史，后续轮次就有上下文了
  if (audioTurn) {
    if (audioUserBubble && !audioTranscript) audioUserBubble.textContent = '🎤 语音消息';
    if (!isError) {
      messages.push({ role: 'user', content: audioTranscript || '（语音消息，内容未转写）' });
    } else if (audioUserBubble) {
      audioUserBubble.remove(); // 失败的语音轮不留占位气泡
    }
  }
  if (!isError && assistantText.trim()) {
    messages.push({ role: 'assistant', content: assistantText });
    saveHistory();
  }
  if (audioTurn && !isError) saveHistory();
  audioTurn = false;
  audioUserBubble = null;
  audioTranscript = '';
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
// 状态栏可带一个"点击动作"（比如引导去系统设置开权限）
let statusAction = null;
function setStatus(t, action) {
  statusEl.textContent = t;
  statusAction = action || null;
  statusEl.style.textDecoration = action ? 'underline' : '';
  statusEl.style.cursor = action ? 'pointer' : '';
}
statusEl.addEventListener('click', () => { if (statusAction) statusAction(); });
function goOpenAppSettings() { if (N && N.openAppSettings) N.openAppSettings(); }

// ---------- 麦克风权限横幅（没权限时一直显示在顶部，点一下就去开启）----------
let micBanner = null;
let micDeniedPermanently = false;
function showMicBanner(text, onTap) {
  if (!micBanner) {
    micBanner = document.createElement('div');
    micBanner.id = 'micBanner';
    micBanner.style.cssText =
      'position:fixed;left:0;right:0;top:0;z-index:9998;background:#c8641e;color:#fff;' +
      'padding:12px 16px;font-size:14px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.4);';
    document.body.appendChild(micBanner);
  }
  micBanner.textContent = text;
  micBanner.onclick = onTap;
  micBanner.style.display = 'block';
}
function hideMicBanner() { if (micBanner) micBanner.style.display = 'none'; }

// 根据 App 眼里的权限状态刷新横幅
function refreshMicBanner(hasPerm) {
  if (!N || !N.hasMicPermission) return;
  const ok = typeof hasPerm === 'boolean' ? hasPerm : N.hasMicPermission();
  if (ok) { hideMicBanner(); return; }
  if (micDeniedPermanently) {
    showMicBanner('⚠️ 麦克风权限被禁止——点这里去系统设置里改成「允许」', goOpenAppSettings);
  } else {
    showMicBanner('⚠️ 还没有麦克风权限——点这里开启（会弹授权框，选「允许」）', () => {
      if (N.requestMic) N.requestMic();
    });
  }
}
// 回到前台（含从设置页返回）时刷新
window.onForeground = function (has) { refreshMicBanner(has === '1'); };
window.onMicPrompt = function () { setStatus('请在弹出的框里点「允许」，之后会自动开始'); };

// ---------- 离线唤醒模型下载进度 ----------
window.onWakeModelProgress = function (pct) {
  const n = parseInt(pct, 10);
  if (n < 0) setStatus('唤醒模型下载完成，正在解压…');
  else setStatus('正在下载离线唤醒模型 ' + n + '%（约 42MB，仅首次，需联网）…');
};
window.onWakeModelReady = function () {
  setStatus('✅ 语音唤醒已就绪！退出 App 后喊唤醒词即可唤起');
};
window.onWakeModelError = function (msg) {
  setStatus('唤醒模型准备失败：' + (msg || '') + '（可稍后在设置里重开重试）');
};

// ---------- 测试唤醒识别（当场看引擎听到了什么）----------
let wakeTesting = false;
const wakeTestBtn = document.getElementById('s_wakeTest');
if (wakeTestBtn) {
  wakeTestBtn.addEventListener('click', () => {
    if (!N || !N.startWakeTest) { showTestResult('请在 App 内使用。', 'err'); return; }
    if (wakeTesting) {
      N.stopWakeTest();
      wakeTesting = false;
      wakeTestBtn.textContent = '🎤 测试唤醒识别';
      showTestResult('已停止测试。', '');
      return;
    }
    wakeTesting = true;
    wakeTestBtn.textContent = '⏹ 停止测试';
    showTestResult('准备中…', '');
    N.startWakeTest((S.wakeWord.value || '你好助手').trim());
  });
}
// 实时显示引擎听到的文字
window.onWakeHeard = function (text) {
  if (!wakeTesting && !/加载|准备|开始|下载/.test(text)) return;
  showTestResult('🎧 听到：' + text, '');
};
window.onWakeTestMatched = function () {
  showTestResult('✅ 听到唤醒词了！识别没问题——如果实际还弹不出来，那是「后台弹出界面/后台运行」权限被 vivo 拦了。', 'ok');
};
function scrollToBottom() { chatEl.scrollTop = chatEl.scrollHeight; }
function showHint() {
  if (document.querySelector('.hint')) return;
  const h = document.createElement('div');
  h.className = 'hint';
  h.textContent = '点击下方麦克风，对我说话吧。我会用语音回答你。\n还能帮你打电话、订闹钟、定时器、发短信、开应用、导航、搜索、调音量/亮度、开手电筒。\n首次使用请先点右上角 ⚙️ 填写接口。';
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
  refreshMicBanner(); // 没麦克风权限则顶部显示横幅
})();
