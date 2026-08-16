import { exec } from 'kernelsu'

const CONTROL_PATH = '/data/adb/modules/vmq_ksu/bin/vmqctl'
const SOURCE_REPOSITORY_URL = 'https://github.com/LeoChen-CoreMind/vmq-ksu-listener'
const COMMANDS = new Set([
  'status',
  'config-get',
  'start',
  'stop',
  'restart',
  'test-connection',
  'test-listener',
  'test-parser',
  'logs',
  'clear-logs',
])

function bridgeExec(command, options) {
  const mock = globalThis.__VMQ_KSU_MOCK__
  return mock?.exec ? mock.exec(command, options) : exec(command, options)
}

const DEFAULT_TIMEOUT_MS = 10_000

function encodeBase64Utf8(value) {
  const bytes = new TextEncoder().encode(String(value))
  let binary = ''
  const chunkSize = 0x8000
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return btoa(binary)
}

export class BridgeError extends Error {
  constructor(kind, message) {
    super(message)
    this.name = 'BridgeError'
    this.kind = kind
  }
}

function redact(value, secrets = []) {
  let message = String(value || '')
  for (const secret of secrets) {
    if (secret) message = message.split(String(secret)).join('[REDACTED]')
  }
  return message
}

function parseResult(result, secrets) {
  if (!result || typeof result.errno !== 'number') {
    throw new BridgeError('protocol', 'KernelSU 返回了无效结果')
  }
  if (result.errno !== 0) {
    throw new BridgeError('exec', redact(result.stderr || `命令执行失败：${result.errno}`, secrets))
  }
  let response
  try {
    response = JSON.parse(result.stdout)
  } catch {
    throw new BridgeError('protocol', 'KernelSU 返回了无法解析的数据')
  }
  if (!response || typeof response.ok !== 'boolean') throw new BridgeError('protocol', 'KernelSU 响应格式不正确')
  if (!response.ok) throw new BridgeError('command', redact(response.error || '命令执行失败', secrets))
  return response.data
}

async function execute(command, options, timeoutMs, secrets = []) {
  let timer
  try {
    const operation = Promise.resolve().then(() => bridgeExec(command, options))
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new BridgeError('timeout', '命令执行超时，请重试')), timeoutMs)
    })
    return parseResult(await Promise.race([operation, timeout]), secrets)
  } catch (error) {
    if (error instanceof BridgeError) throw error
    throw new BridgeError('exec', redact(error?.message || 'KernelSU bridge 不可用', secrets))
  } finally {
    clearTimeout(timer)
  }
}

export async function runControl(command, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  if (!COMMANDS.has(command)) throw new BridgeError('unsupported', `不支持的命令：${command}`)
  return execute(`${CONTROL_PATH} ${command}`, undefined, timeoutMs)
}

export async function writeConfig(config, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  const key = String(config?.key || '')
  return execute(`${CONTROL_PATH} config-set`, {
    env: { VMQ_CONFIG_B64: encodeBase64Utf8(JSON.stringify(config)) },
  }, timeoutMs, [key])
}

export async function openExternalUrl(url, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  if (url !== SOURCE_REPOSITORY_URL) throw new BridgeError('unsupported', '不支持打开该外部地址')
  let timer
  try {
    const quotedUrl = `'${url.replaceAll("'", "'\\''")}'`
    const command = `am start --user 0 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d ${quotedUrl}`
    const operation = Promise.resolve().then(() => bridgeExec(command))
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new BridgeError('timeout', '打开外部浏览器超时')), timeoutMs)
    })
    const result = await Promise.race([operation, timeout])
    if (!result || typeof result.errno !== 'number') throw new BridgeError('protocol', 'KernelSU 返回了无效结果')
    if (result.errno !== 0) throw new BridgeError('exec', result.stderr || '无法打开外部浏览器')
  } finally {
    clearTimeout(timer)
  }
}
