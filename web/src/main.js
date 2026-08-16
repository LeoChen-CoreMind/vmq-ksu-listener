import {
  Activity,
  BellRing,
  Camera,
  createIcons,
  Eye,
  EyeOff,
  FileText,
  Github,
  Image,
  LayoutDashboard,
  RefreshCw,
  RotateCw,
  ScanLine,
  Save,
  Settings2,
  Trash2,
} from 'lucide'
import { openExternalUrl, runControl, writeConfig } from './bridge.js'
import { parseConfigValue, QrScanner } from './qr.js'
import { renderApp, statusPresentation } from './views.js'
import './styles.css'

const iconSet = { Activity, BellRing, Camera, Eye, EyeOff, FileText, Github, Image, LayoutDashboard, RefreshCw, RotateCw, ScanLine, Save, Settings2, Trash2 }
const app = document.querySelector('#app')
const MAX_LOG_LINES = 200
const MAX_LOG_BYTES = 32 * 1024
const STATUS_REFRESH_MS = 5000
const LOG_REFRESH_MS = 5000
const UPTIME_REFRESH_MS = 1000
const STATUS_CACHE_KEY = 'vmq.lastStatus'
const AUTO_LOG_REFRESH_KEY = 'vmq.autoLogRefresh'
const SOURCE_REPOSITORY_URL = 'https://github.com/LeoChen-CoreMind/vmq-ksu-listener'

const state = {
  activeTab: 'overview', status: readCachedStatus(), config: null, desiredOverride: null,
  logs: '', logsTruncated: false, logsLoading: false, logsClearing: false, logVersion: 0,
  autoLogRefresh: readAutoLogRefresh(), keyVisible: false, statusVersion: 0,
  commandError: '', commandResult: '', pendingQr: null, scanner: null,
}
const tasks = new Map()
const controlTasks = new Map()
let statusRequest = null
let statusRefreshQueued = false
let statusRefreshShouldReport = false
let logsRequest = null
let statusTimer = null
let logTimer = null
let uptimeTimer = null

function render() {
  if (!app) throw new Error('WebUI 根节点不存在')
  app.innerHTML = renderApp(state)
  hydrateConfig()
  bindEvents()
  createIcons({ icons: iconSet, attrs: { 'stroke-width': 1.8 } })
  patchStatus()
  patchLogs()
  patchMessages()
}

function hydrateConfig() {
  if (!state.config) return
  const form = document.querySelector('#config-form')
  if (!form) return
  for (const [name, value] of Object.entries(state.config)) {
    const control = form.elements.namedItem(name)
    if (!control || name === 'configured') continue
    if (control.type === 'checkbox') control.checked = Boolean(value)
    else control.value = value ?? ''
  }
}

function bindEvents() {
  document.querySelectorAll('[data-tab]').forEach((button) => button.addEventListener('click', () => switchTab(button.dataset.tab)))
  document.querySelectorAll('[data-command]').forEach((button) => button.addEventListener('click', () => handleCommand(button.dataset.command)))
  document.querySelector('[data-action="service-toggle"]')?.addEventListener('change', handleServiceToggle)
  document.querySelector('[data-testid="source-link"]')?.addEventListener('click', openSourceRepository)
  document.querySelector('[data-action="toggle-key"]')?.addEventListener('click', (event) => {
    const input = document.querySelector('input[name="key"]')
    if (!input) return
    state.keyVisible = !state.keyVisible
    input.type = state.keyVisible ? 'text' : 'password'
    event.currentTarget.setAttribute('aria-label', state.keyVisible ? '隐藏密钥' : '显示密钥')
    event.currentTarget.setAttribute('title', state.keyVisible ? '隐藏密钥' : '显示密钥')
    event.currentTarget.innerHTML = `<i data-lucide="${state.keyVisible ? 'eye-off' : 'eye'}"></i>`
    createIcons({ icons: iconSet, attrs: { 'stroke-width': 1.8 } })
  })
  document.querySelector('#config-form')?.addEventListener('submit', saveConfiguration)
  document.querySelector('[data-action="scan-camera"]')?.addEventListener('click', startCameraScan)
  document.querySelector('[data-action="scan-photo"]')?.addEventListener('click', () => document.querySelector('#camera-file')?.click())
  document.querySelector('[data-action="scan-image"]')?.addEventListener('click', () => document.querySelector('#image-file')?.click())
  document.querySelector('#camera-file')?.addEventListener('change', (event) => decodeFile(event.target.files[0]))
  document.querySelector('#image-file')?.addEventListener('change', (event) => decodeFile(event.target.files[0]))
  document.querySelector('[data-action="close-qr"]')?.addEventListener('click', closeQrDialog)
  document.querySelector('[data-action="cancel-qr"]')?.addEventListener('click', closeQrConfirm)
  document.querySelector('[data-action="apply-qr"]')?.addEventListener('click', applyQrConfig)
  document.querySelector('[data-action="auto-log-refresh"]')?.addEventListener('change', (event) => {
    state.autoLogRefresh = event.target.checked
    writeAutoLogRefresh(state.autoLogRefresh)
    if (state.autoLogRefresh) startLogRefresh(true)
    else stopLogRefresh()
  })
}

async function openSourceRepository(event) {
  event.preventDefault()
  try {
    await openExternalUrl(SOURCE_REPOSITORY_URL)
  } catch {
    window.open(SOURCE_REPOSITORY_URL, '_blank', 'noopener,noreferrer')
  }
}

function patchStatus() {
  const view = statusPresentation(state)
  const badge = document.querySelector('[data-testid="service-state"]')
  if (badge) badge.className = `state-badge ${view.tone}`
  setText('[data-status="service-label"]', view.serviceLabel)
  setText('[data-status="service"]', view.serviceLabel, view.serviceTone)
  setText('[data-status="heartbeat"]', view.heartbeatLabel, view.heartbeatTone)
  setText('[data-status="listener"]', view.listenerLabel, view.listenerTone)
  setMetric('uptime', view.uptime)
  setMetric('restart-count', view.restartCount)
  setMetric('pending-count', view.pending, view.pendingTone)
  setMetric('failed-count', view.failed, view.failedTone)
  setText('[data-status="last-heartbeat"]', view.lastHeartbeat)
  setText('[data-status="last-delivery"]', view.lastDelivery)
  const toggle = document.querySelector('[data-action="service-toggle"]')
  if (toggle) toggle.checked = view.desired === 'running'
}

function patchUptime() {
  setMetric('uptime', statusPresentation(state).uptime)
}

function patchLogs() {
  const output = document.querySelector('#log-output')
  if (output) output.textContent = state.logs || '暂无日志'
  const marker = document.querySelector('[data-testid="log-marker"]')
  if (marker) marker.hidden = !state.logsTruncated
}

function patchMessages() {
  document.querySelectorAll('[data-testid="command-result"]').forEach((node) => { node.textContent = state.commandResult })
  document.querySelectorAll('[data-testid="command-error"], [data-testid="config-error"]').forEach((node) => { node.textContent = state.commandError })
}

function setText(selector, value, tone) {
  const node = document.querySelector(selector)
  if (!node) return
  node.textContent = String(value)
  if (tone !== undefined) node.className = tone
}

function setMetric(testId, value, tone) {
  const node = document.querySelector(`[data-testid="${testId}"]`)
  if (!node) return
  node.textContent = String(value)
  if (tone !== undefined && node.parentElement) node.parentElement.className = `metric ${tone}`.trim()
}

async function loadConfiguration() {
  try {
    state.config = { ...await runControl('config-get') }
    hydrateConfig()
    patchStatus()
  } catch (error) {
    showError(error.message)
  }
}

function requestStatusRefresh({ silent = true, invalidate = false } = {}) {
  if (invalidate) {
    state.statusVersion++
    statusRefreshQueued = true
    if (!silent) statusRefreshShouldReport = true
  }
  if (statusRequest) return statusRequest
  statusRefreshQueued = false
  statusRefreshShouldReport = false
  const version = state.statusVersion
  statusRequest = runControl('status')
    .then((status) => {
      if (version !== state.statusVersion) return
      state.status = status
      state.desiredOverride = null
      cacheStatus(status)
      patchStatus()
    })
    .catch((error) => { if (!silent) showError(error.message) })
    .finally(() => {
      statusRequest = null
      if (statusRefreshQueued && document.visibilityState === 'visible') {
        const nextSilent = !statusRefreshShouldReport
        statusRefreshQueued = false
        statusRefreshShouldReport = false
        void requestStatusRefresh({ silent: nextSilent })
      }
    })
  return statusRequest
}

function startStatusRefresh(immediate = false) {
  stopStatusRefresh()
  if (document.visibilityState !== 'visible') return
  if (immediate) void requestStatusRefresh({ silent: true })
  statusTimer = setTimeout(async () => {
    await requestStatusRefresh({ silent: true })
    startStatusRefresh(false)
  }, STATUS_REFRESH_MS)
}

function stopStatusRefresh() {
  if (statusTimer) clearTimeout(statusTimer)
  statusTimer = null
}

function startUptimeRefresh() {
  stopUptimeRefresh()
  if (document.visibilityState !== 'visible') return
  uptimeTimer = setInterval(patchUptime, UPTIME_REFRESH_MS)
}

function stopUptimeRefresh() {
  if (uptimeTimer) clearInterval(uptimeTimer)
  uptimeTimer = null
}

function switchTab(tab) {
  if (!['overview', 'config', 'logs'].includes(tab) || tab === state.activeTab) return
  stopLogRefresh()
  closeQrDialog()
  state.activeTab = tab
  state.commandError = ''
  state.commandResult = ''
  render()
  if (tab === 'logs') {
    if (state.autoLogRefresh) startLogRefresh(true)
    else void loadLogs({ reportErrors: true })
  }
}

function loadLogs({ reportErrors = true } = {}) {
  if (state.logsClearing || logsRequest) return logsRequest || Promise.resolve()
  state.logsLoading = true
  const version = state.logVersion
  logsRequest = runControl('logs')
    .then((result) => {
      if (version !== state.logVersion) return
      const bounded = boundLogText(result.text)
      state.logs = bounded.text
      state.logsTruncated = Boolean(result.truncated || bounded.truncated)
      if (state.activeTab === 'logs') patchLogs()
    })
    .catch((error) => { if (reportErrors) showError(error.message) })
    .finally(() => {
      state.logsLoading = false
      logsRequest = null
    })
  return logsRequest
}

function boundLogText(value) {
  const normalized = String(value || '').replace(/\r\n?/g, '\n')
  const lines = normalized.split('\n')
  if (lines.at(-1) === '') lines.pop()
  let truncated = lines.length > MAX_LOG_LINES
  const candidates = lines.slice(-MAX_LOG_LINES)
  const selected = []
  let bytes = 0

  for (let index = candidates.length - 1; index >= 0; index--) {
    const line = candidates[index]
    const separatorBytes = selected.length ? 1 : 0
    const lineBytes = new TextEncoder().encode(line).length
    if (bytes + separatorBytes + lineBytes <= MAX_LOG_BYTES) {
      selected.unshift(line)
      bytes += separatorBytes + lineBytes
      continue
    }
    if (!selected.length) selected.unshift(takeUtf8Tail(line, MAX_LOG_BYTES))
    truncated = true
    break
  }
  return { text: selected.reverse().join('\n'), truncated }
}

function takeUtf8Tail(value, maxBytes) {
  const points = Array.from(value)
  let low = 0
  let high = points.length
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (new TextEncoder().encode(points.slice(middle).join('')).length <= maxBytes) high = middle
    else low = middle + 1
  }
  return points.slice(low).join('')
}

function startLogRefresh(immediate = false) {
  stopLogRefresh()
  if (!state.autoLogRefresh || state.activeTab !== 'logs' || document.visibilityState !== 'visible') return
  if (immediate) void loadLogs({ reportErrors: false })
  logTimer = setTimeout(async () => {
    await loadLogs({ reportErrors: false })
    startLogRefresh(false)
  }, LOG_REFRESH_MS)
}

function stopLogRefresh() {
  if (logTimer) clearTimeout(logTimer)
  logTimer = null
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    startStatusRefresh(true)
    startUptimeRefresh()
    if (state.activeTab === 'logs' && state.autoLogRefresh) startLogRefresh(true)
  } else {
    stopStatusRefresh()
    stopUptimeRefresh()
    stopLogRefresh()
  }
})

function runBackground(key, operation, onError = (error) => showError(error.message)) {
  if (tasks.has(key)) return false
  const task = Promise.resolve().then(operation).catch(onError).finally(() => tasks.delete(key))
  tasks.set(key, task)
  return true
}

function runControlOnce(command, options) {
  if (controlTasks.has(command)) return controlTasks.get(command)
  const task = runControl(command, options).finally(() => {
    if (controlTasks.get(command) === task) controlTasks.delete(command)
  })
  controlTasks.set(command, task)
  return task
}

async function runControlAfterExisting(command, options) {
  const existing = controlTasks.get(command)
  if (existing) {
    try { await existing } catch { /* This operation still needs its own command attempt. */ }
  }
  return runControlOnce(command, options)
}

function saveConfiguration(event) {
  event.preventDefault()
  clearMessages()
  let config
  try {
    config = readForm({ validate: true })
  } catch (validationError) {
    showError(validationError.message)
    return
  }
  const wasRunning = desiredState() === 'running'
  runBackground('save-config', async () => {
    try {
      await writeConfig(config)
    } catch (error) {
      throw new Error(`配置保存失败：${error.message}`)
    }
    state.config = { ...config }
    showResult(wasRunning ? '配置已保存，服务正在后台重启' : '配置已保存，服务保持停止')
    if (wasRunning) {
      try {
        await runControlAfterExisting('restart')
      } catch (error) {
        throw new Error(`配置已保存，但服务重启失败：${error.message}`)
      }
    }
    void requestStatusRefresh({ silent: false, invalidate: true })
  })
}

function readForm({ validate }) {
  const form = document.querySelector('#config-form')
  if (!form) return state.config ? { ...state.config } : null
  const data = new FormData(form)
  const baseUrl = String(data.get('baseUrl') || '').trim()
  const key = String(data.get('key') || '').trim()
  if (validate) {
    let url
    try { url = new URL(baseUrl) } catch { throw new Error('请输入有效的 HTTP 或 HTTPS 服务地址') }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash || url.search) throw new Error('服务地址必须是无账号、查询参数和片段的 HTTP(S) 地址')
    if (!key) throw new Error('通信密钥不能为空')
    if (!form.checkValidity()) throw new Error('请检查数值范围')
  }
  return { baseUrl, key, enabled: state.config?.enabled ?? true, wechatEnabled: form.elements.wechatEnabled.checked, wecomEnabled: form.elements.wecomEnabled.checked, alipayEnabled: form.elements.alipayEnabled.checked, heartbeatSeconds: Number(data.get('heartbeatSeconds')), connectTimeoutSeconds: Number(data.get('connectTimeoutSeconds')), readTimeoutSeconds: Number(data.get('readTimeoutSeconds')), maxRetries: Number(data.get('maxRetries')), logLevel: String(data.get('logLevel')) }
}

function handleServiceToggle(event) {
  if (tasks.has('service-toggle')) {
    patchStatus()
    return
  }
  const enabled = event.target.checked
  const previousDesired = desiredState()
  clearMessages()
  state.statusVersion++
  setDesiredState(enabled)
  patchStatus()
  runBackground('service-toggle', async () => {
    if (enabled && !isConfigured()) throw new Error('请先完成 V免签配置')
    await runControlOnce(enabled ? 'start' : 'stop')
    showResult(enabled ? '服务开启命令已在后台执行' : '服务关闭命令已在后台执行')
    void requestStatusRefresh({ silent: false, invalidate: true })
  }, (error) => {
    state.desiredOverride = previousDesired
    patchStatus()
    showError(error.message)
  })
}

function setDesiredState(enabled) {
  state.desiredOverride = enabled ? 'running' : 'stopped'
  if (state.status) {
    state.status = { ...state.status, supervisor: { ...(state.status.supervisor || {}), desiredState: state.desiredOverride } }
  }
}

function desiredState() {
  return state.desiredOverride || state.status?.supervisor?.desiredState || (state.config?.enabled ? 'running' : 'stopped')
}

function isConfigured() {
  return Boolean(state.status?.config?.configured || state.config?.configured || String(state.config?.key || '').trim())
}

function handleCommand(command) {
  clearMessages()
  if (command === 'refresh-status') {
    void requestStatusRefresh({ silent: false, invalidate: true })
    return
  }
  if (command === 'logs') {
    void loadLogs({ reportErrors: true })
    return
  }
  if (command === 'clear-logs') {
    if (!window.confirm('确定要清空全部日志吗？')) return
    runBackground('clear-logs', clearLogs)
    return
  }
  runBackground(command, async () => {
    const options = command === 'test-connection' ? { timeoutMs: heartbeatTimeoutMs() } : undefined
    const result = await runControlOnce(command, options)
    if (command === 'test-listener') {
      state.status ||= {}
      state.status.listener = { ...(state.status.listener || {}), health: result }
      patchStatus()
    }
    showResult(resultMessage(command, result))
    void requestStatusRefresh({ silent: false, invalidate: true })
  })
}

function heartbeatTimeoutMs() {
  const connect = Number(state.config?.connectTimeoutSeconds ?? state.status?.config?.connectTimeoutSeconds ?? 10)
  const read = Number(state.config?.readTimeoutSeconds ?? state.status?.config?.readTimeoutSeconds ?? 30)
  return Math.min(245_000, Math.max(10_000, (connect + read) * 1000 + 5000))
}

async function clearLogs() {
  const previous = { text: state.logs, truncated: state.logsTruncated }
  state.logsClearing = true
  state.logVersion++
  state.logs = ''
  state.logsTruncated = false
  patchLogs()
  try {
    await runControlOnce('clear-logs')
    showResult('日志已清空')
  } catch (error) {
    state.logs = previous.text
    state.logsTruncated = previous.truncated
    patchLogs()
    throw error
  } finally {
    state.logsClearing = false
    if (state.autoLogRefresh) startLogRefresh(false)
  }
}

function resultMessage(command, result) {
  if (command === 'test-connection') return result.success ? `连接测试成功${result.message ? `：${result.message}` : ''}` : `连接失败：${result.message || '未知错误'}`
  if (command === 'test-listener') return result.healthy ? '通知监听正常' : `通知监听异常：${result.message || '未连接'}`
  if (command === 'test-parser') return `解析测试通过：接受 ${result.accepted}，拒绝 ${result.rejected}`
  return command === 'start' ? '启动命令已发送' : command === 'stop' ? '停止命令已发送' : '重启命令已发送'
}

function clearMessages() {
  state.commandError = ''
  state.commandResult = ''
  patchMessages()
}

function showError(message) {
  state.commandError = String(message || '未知错误')
  patchMessages()
}

function showResult(message) {
  state.commandResult = String(message || '')
  patchMessages()
}

function readCachedStatus() {
  try {
    const raw = sessionStorage.getItem(STATUS_CACHE_KEY)
    return raw ? JSON.parse(raw, (key, value) => key === 'key' ? undefined : value) : null
  } catch {
    return null
  }
}

function cacheStatus(status) {
  try {
    sessionStorage.setItem(STATUS_CACHE_KEY, JSON.stringify(status, (key, value) => key === 'key' ? undefined : value))
  } catch {
    // Storage can be unavailable in restricted WebViews; live refresh still works.
  }
}

function readAutoLogRefresh() {
  try { return localStorage.getItem(AUTO_LOG_REFRESH_KEY) === 'true' } catch { return false }
}

function writeAutoLogRefresh(enabled) {
  try { localStorage.setItem(AUTO_LOG_REFRESH_KEY, String(enabled)) } catch { /* Keep the in-memory preference. */ }
}

async function startCameraScan() {
  const dialog = document.querySelector('#qr-dialog')
  const error = document.querySelector('[data-testid="qr-error"]')
  error.textContent = ''
  dialog.showModal()
  state.scanner = new QrScanner()
  try { await state.scanner.start(document.querySelector('#qr-video'), (raw) => { closeQrDialog(); showQrPreview(raw) }) }
  catch (scanError) { openSystemCamera(scanError.message) }
}

function openSystemCamera(reason) {
  closeQrDialog()
  const input = document.querySelector('#camera-file')
  if (!input) {
    showError(`${reason}，系统相机入口不可用`)
    return
  }
  input.value = ''
  input.click()
  showError(`${reason}，已切换到系统相机拍照`)
}

async function decodeFile(file) {
  if (!file) return
  try { state.scanner ||= new QrScanner(); showQrPreview(await state.scanner.decodeImage(file)) }
  catch (scanError) { showError(`二维码读取失败：${scanError.message}`) }
}

function showQrPreview(raw) {
  try {
    state.pendingQr = parseConfigValue(raw)
    document.querySelector('[data-testid="qr-base-url"]').textContent = state.pendingQr.baseUrl
    document.querySelector('[data-testid="qr-key"]').textContent = `${state.pendingQr.key.slice(0, 3)}••••${state.pendingQr.key.slice(-2)}`
    document.querySelector('#qr-confirm').showModal()
  } catch (error) { showError(error.message) }
}

function applyQrConfig() {
  if (!state.pendingQr) return
  const form = document.querySelector('#config-form')
  form.elements.baseUrl.value = state.pendingQr.baseUrl
  form.elements.key.value = state.pendingQr.key
  state.pendingQr = null
  closeQrConfirm()
  showResult('二维码配置已填入，请保存后生效')
}

function closeQrDialog() {
  state.scanner?.stop()
  state.scanner = null
  const dialog = document.querySelector('#qr-dialog')
  if (dialog?.open) dialog.close()
}

function closeQrConfirm() {
  const dialog = document.querySelector('#qr-confirm')
  if (dialog?.open) dialog.close()
  state.pendingQr = null
}

try {
  render()
  document.documentElement.dataset.vmqReady = 'true'
  startStatusRefresh(true)
  startUptimeRefresh()
  void loadConfiguration()
} catch (error) {
  globalThis.__VMQ_SHOW_FATAL__?.(error?.message || error)
}
