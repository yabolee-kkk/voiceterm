'use strict';
// cc-connect Bridge Protocol 字段集中定义（v1.0-draft，字段只在这里出现一次）

const IN  = { REGISTER:'register', MESSAGE:'message', CARD_ACTION:'card_action', PREVIEW_ACK:'preview_ack', PING:'ping' };
const OUT = { REGISTER_ACK:'register_ack', REPLY:'reply', REPLY_STREAM:'reply_stream',
              PREVIEW_START:'preview_start', UPDATE_MESSAGE:'update_message', DELETE_MESSAGE:'delete_message',
              CARD:'card', BUTTONS:'buttons', TYPING_START:'typing_start', TYPING_STOP:'typing_stop',
              AUDIO:'audio', IMAGE:'image', FILE:'file', PONG:'pong', ERROR:'error' };

const PLATFORM = 'phone';

const CAPABILITIES = ['text','image','file','audio','card','buttons',
                      'typing','update_message','preview','delete_message','reconstruct_reply'];

// session_key = phone:{agentId}[#name]:{deviceId}
function sessionKey(agentId, deviceId, name) {
  return `phone:${name ? `${agentId}#${name}` : agentId}:${deviceId}`;
}

function parseSessionKey(key) {
  const parts = String(key || '').split(':');
  const scope = parts[1] || '';
  const [agentId, name] = scope.split('#');
  return { platform: parts[0], agentId, name: name || null, deviceId: parts.slice(2).join(':') };
}

function buildRegister() {
  return { type: IN.REGISTER, platform: PLATFORM, capabilities: CAPABILITIES,
           metadata: { version: '1.0.1', impl: 'voiceterm-hub' } };
}

function buildMessage({ msgId, agentId, deviceId, name, text, audio, images, files }) {
  return {
    type: IN.MESSAGE,
    msg_id: msgId,
    session_key: sessionKey(agentId, deviceId, name),
    project: agentId,                 // ★ 路由靠这个
    user_id: deviceId,
    user_name: 'owner',
    content: text,
    reply_ctx: msgId,
    // cc-connect 的 bridgeImageData/bridgeFileData：{mime_type, data(base64), file_name}
    images: images || [], files: files || [],
    // 语音消息：{ mime_type, data(base64), format, duration(ms) }
    // cc-connect 见到 audio 会自己转写（core/engine.go handleVoiceMessage），
    // 并把这条标记为 FromVoice → tts_mode=voice_only 时它的回复会用 voice 帧推回来
    ...(audio ? { audio } : { audio: null }),
  };
}

function buildCardAction({ agentId, deviceId, name, action, replyCtx }) {
  return { type: IN.CARD_ACTION, session_key: sessionKey(agentId, deviceId, name),
           project: agentId, action, reply_ctx: replyCtx || '' };
}

function buildPreviewAck(refId) {
  return { type: IN.PREVIEW_ACK, ref_id: refId, preview_handle: 'p-' + refId };
}

module.exports = { IN, OUT, PLATFORM, CAPABILITIES,
                   sessionKey, parseSessionKey, buildRegister, buildMessage, buildCardAction, buildPreviewAck };
