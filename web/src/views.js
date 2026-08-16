export function renderApp(state) {
  return `${renderHeader(state)}<main class="app-main">${renderTab(state)}</main>${renderNavigation(state)}${renderQrDialogs()}`
}

export function renderHeader(state) {
  const view = statusPresentation(state)
  return `<header class="app-header"><div class="shell header-row"><div><h1>V免签通知监听</h1><p class="subtitle">微信 · 企业微信 · 支付宝</p></div><div class="header-controls"><div class="state-badge ${view.tone}" data-testid="service-state"><span></span><b data-status="service-label">${view.serviceLabel}</b></div><label class="master-switch" title="服务总开关"><input data-testid="service-toggle" data-action="service-toggle" type="checkbox" ${view.desired === 'running' ? 'checked' : ''}><span class="switch-track"></span><span class="switch-label">服务</span></label></div></div></header>`
}

function renderTab(state) {
  if (state.activeTab === 'config') return renderConfig(state)
  if (state.activeTab === 'logs') return renderLogs(state)
  return renderOverview(state)
}

export function renderOverview(state) {
  const view = statusPresentation(state)
  return `<section class="tab-panel shell" data-testid="overview-tab" aria-labelledby="overview-title"><div class="section-heading"><div><p class="eyebrow">运行状态</p><h2 id="overview-title">服务概览</h2></div><button class="icon-button" type="button" data-command="refresh-status" aria-label="刷新状态" title="刷新状态"><i data-lucide="refresh-cw"></i></button></div><div class="health-grid"><div class="health-item"><span>服务</span><strong data-status="service" class="${view.serviceTone}">${view.serviceLabel}</strong></div><div class="health-item"><span>心跳</span><strong data-status="heartbeat" class="${view.heartbeatTone}">${view.heartbeatLabel}</strong></div><div class="health-item"><span>监听</span><strong data-testid="listener-health" data-status="listener" class="${view.listenerTone}">${view.listenerLabel}</strong></div></div><div class="metrics">${metric('运行时长', view.uptime, 'uptime')}${metric('重启次数', view.restartCount, 'restart-count')}${metric('待发送', view.pending, 'pending-count', view.pendingTone)}${metric('失败队列', view.failed, 'failed-count', view.failedTone)}</div><div class="status-lines"><div><span>最近心跳</span><strong data-status="last-heartbeat">${view.lastHeartbeat}</strong></div><div><span>最近投递</span><strong data-status="last-delivery">${escapeHtml(view.lastDelivery)}</strong></div></div><div class="quick-actions"><button class="control-button" type="button" data-testid="heartbeat-check" data-command="test-connection"><i data-lucide="activity"></i><span>检测心跳</span></button><button class="control-button" type="button" data-testid="listener-check" data-command="test-listener"><i data-lucide="bell-ring"></i><span>检测监听</span></button><button class="control-button" type="button" data-command="restart"><i data-lucide="rotate-cw"></i><span>重启服务</span></button></div><p class="command-result" data-testid="command-result" aria-live="polite">${escapeHtml(state.commandResult)}</p><p class="inline-error" data-testid="command-error" aria-live="assertive">${escapeHtml(state.commandError)}</p><a class="source-link" data-testid="source-link" href="https://github.com/LeoChen-CoreMind/vmq-ksu-listener" target="_blank" rel="noopener noreferrer"><i data-lucide="github"></i><span>开源地址</span></a></section>`
}

export function renderConfig(state) {
  return `<section class="tab-panel shell" data-testid="config-tab" aria-labelledby="config-title"><div class="section-heading compact"><div><p class="eyebrow">连接设置</p><h2 id="config-title">V免签配置</h2></div></div><div class="config-imports" aria-label="二维码配置"><button class="secondary-button" type="button" data-action="scan-camera"><i data-lucide="camera"></i><span>相机扫码</span></button><button class="secondary-button" type="button" data-action="scan-photo"><i data-lucide="scan-line"></i><span>拍照扫码</span></button><button class="secondary-button" type="button" data-action="scan-image"><i data-lucide="image"></i><span>导入二维码</span></button><input id="camera-file" type="file" accept="image/*" capture="environment" hidden><input id="image-file" type="file" accept="image/*" hidden></div><form id="config-form" novalidate><div class="form-grid"><label class="field span-2">服务地址<input name="baseUrl" type="url" inputmode="url" autocomplete="url" required></label><label class="field span-2">通信密钥<span class="secret-input"><input name="key" aria-label="通信密钥" type="${state.keyVisible ? 'text' : 'password'}" autocomplete="off" required><button class="input-icon" type="button" data-action="toggle-key" aria-label="${state.keyVisible ? '隐藏密钥' : '显示密钥'}" title="${state.keyVisible ? '隐藏密钥' : '显示密钥'}"><i data-lucide="${state.keyVisible ? 'eye-off' : 'eye'}"></i></button></span></label>${numberField('心跳间隔（秒）', 'heartbeatSeconds', 15, 3600)}${numberField('连接超时（秒）', 'connectTimeoutSeconds', 1, 120)}${numberField('读取超时（秒）', 'readTimeoutSeconds', 1, 120)}${numberField('最大重试次数', 'maxRetries', 0, 30)}<label class="field">日志级别<select name="logLevel"><option value="error">错误</option><option value="info">信息</option><option value="debug">调试</option></select></label></div><details class="advanced-settings"><summary>通知来源</summary><div class="toggles">${toggle('wechatEnabled', '微信')}${toggle('wecomEnabled', '企业微信')}${toggle('alipayEnabled', '支付宝')}</div></details><p class="command-result" data-testid="command-result" aria-live="polite">${escapeHtml(state.commandResult)}</p><p class="inline-error" data-testid="config-error" aria-live="assertive">${escapeHtml(state.commandError)}</p><div class="form-actions"><button class="primary-button" type="submit"><i data-lucide="save"></i><span>保存配置</span></button></div></form></section>`
}

export function renderLogs(state) {
  return `<section class="tab-panel shell" data-testid="logs-tab" aria-labelledby="logs-title"><div class="section-heading"><div><p class="eyebrow">诊断</p><h2 id="logs-title">运行日志</h2></div><div class="icon-actions"><button class="icon-button" type="button" data-command="logs" aria-label="刷新日志" title="刷新日志"><i data-lucide="refresh-cw"></i></button><button class="icon-button danger" type="button" data-command="clear-logs" aria-label="清空日志" title="清空日志"><i data-lucide="trash-2"></i></button></div></div><div class="log-toolbar"><label class="toggle compact-toggle"><input data-action="auto-log-refresh" type="checkbox" ${state.autoLogRefresh ? 'checked' : ''}><span class="toggle-track"></span><span>自动刷新</span></label><span class="log-marker" data-testid="log-marker" ${state.logsTruncated ? '' : 'hidden'}>仅显示最新日志</span></div><pre id="log-output" tabindex="0"></pre><p class="inline-error" data-testid="command-error" aria-live="assertive">${escapeHtml(state.commandError)}</p><p class="command-result" data-testid="command-result" aria-live="polite">${escapeHtml(state.commandResult)}</p></section>`
}

export function renderNavigation(state) {
  const tabs = [['overview', 'layout-dashboard', '概览'], ['config', 'settings-2', '配置'], ['logs', 'file-text', '日志']]
  return `<nav class="bottom-nav" aria-label="主导航">${tabs.map(([tab, icon, label]) => `<button type="button" data-tab="${tab}" class="${state.activeTab === tab ? 'active' : ''}" aria-current="${state.activeTab === tab ? 'page' : 'false'}"><i data-lucide="${icon}"></i><span>${label}</span></button>`).join('')}</nav>`
}

export function renderQrDialogs() {
  return `<dialog id="qr-dialog" class="qr-dialog"><div class="qr-dialog-body"><h2>相机扫码</h2><video id="qr-video" autoplay muted playsinline></video><p class="inline-error" data-testid="qr-error"></p><button class="secondary-button" type="button" data-action="close-qr">关闭</button></div></dialog><dialog id="qr-confirm" class="qr-dialog"><div class="qr-dialog-body"><h2>确认二维码配置</h2><p>服务地址：<strong data-testid="qr-base-url"></strong></p><p>通信密钥：<strong data-testid="qr-key"></strong></p><div class="form-actions"><button class="secondary-button" type="button" data-action="cancel-qr">取消</button><button class="primary-button" type="button" data-action="apply-qr">应用</button></div></div></dialog>`
}

function metric(label, value, testId = '', tone = '') {
  return `<div class="metric ${tone}"><span>${label}</span><strong${testId ? ` data-testid="${testId}"` : ''}>${value}</strong></div>`
}

export function statusPresentation(state, now = Date.now()) {
  const known = Boolean(state.status)
  const supervisor = state.status?.supervisor || {}
  const listener = state.status?.listener || {}
  const running = known && Boolean(listener.running && supervisor.pid > 0 && supervisor.listenerPid > 0)
  const desired = state.desiredOverride || supervisor.desiredState || (state.config?.enabled ? 'running' : 'stopped')
  const listenerHealth = listener.health?.healthy ?? listener.healthy ?? listener.frameworkConnected ?? listener.connected
  return {
    known,
    running,
    desired,
    serviceLabel: known ? (running ? '运行中' : '已停止') : '未知',
    tone: known ? (running ? 'success' : 'muted') : 'neutral',
    serviceTone: known ? (running ? 'ok' : 'muted') : 'muted',
    heartbeatLabel: known ? (listener.lastHeartbeatSuccess ? '正常' : '待检测') : '--',
    heartbeatTone: known && listener.lastHeartbeatSuccess ? 'ok' : 'warn',
    listenerLabel: known ? (listenerHealth ? '已连接' : '未连接') : '--',
    listenerTone: known && listenerHealth ? 'ok' : 'warn',
    uptime: known ? (running && supervisor.startedAt ? formatDuration(now - supervisor.startedAt) : '0 秒') : '--',
    restartCount: known ? (supervisor.restartCount ?? 0) : '--',
    pending: known ? (listener.pending ?? 0) : '--',
    failed: known ? (listener.failed ?? 0) : '--',
    pendingTone: known && (listener.pending ?? 0) > 0 ? 'amber' : '',
    failedTone: known && (listener.failed ?? 0) > 0 ? 'red' : '',
    lastHeartbeat: known ? (listener.lastHeartbeatAt ? formatTime(listener.lastHeartbeatAt) : '暂无') : '--',
    lastDelivery: known ? (listener.lastDeliveryMessage || '暂无') : '--',
  }
}

function numberField(label, name, min, max) {
  return `<label class="field">${label}<input name="${name}" type="number" min="${min}" max="${max}" step="1" required></label>`
}

function toggle(name, label) {
  return `<label class="toggle"><input name="${name}" type="checkbox"><span class="toggle-track"></span><span>${label}</span></label>`
}

function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value))
}

function formatDuration(milliseconds) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character])
}
