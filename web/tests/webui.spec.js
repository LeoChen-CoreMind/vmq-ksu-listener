import { expect, test } from '@playwright/test'

const config = {
  baseUrl: 'https://vpay.example',
  key: 'secret-key',
  enabled: true,
  wechatEnabled: true,
  wecomEnabled: true,
  alipayEnabled: true,
  heartbeatSeconds: 50,
  connectTimeoutSeconds: 10,
  readTimeoutSeconds: 30,
  maxRetries: 8,
  logLevel: 'info',
}

async function installMock(page, overrides = {}) {
  await page.addInitScript(({ config, overrides }) => {
    let statusCalls = 0
    let status = {
      supervisor: { pid: 4100, listenerPid: 4101, startedAt: Date.now() - 65_000, restartCount: 2, desiredState: 'running' },
      listener: {
        running: true,
        pending: 3,
        failed: 1,
        lastHeartbeatAt: Date.now() - 5_000,
        lastHeartbeatSuccess: true,
        lastDeliveryMessage: 'success',
      },
      config: { ...config, key: undefined, configured: true },
    }
    globalThis.__VMQ_CALLS__ = []
    globalThis.__VMQ_KSU_MOCK__ = {
      exec: async (command, options = {}) => {
        globalThis.__VMQ_CALLS__.push({ command, options })
        if (overrides.delay) await new Promise((resolve) => setTimeout(resolve, overrides.delay))
        if (overrides.deferCommand && command.endsWith(overrides.deferCommand)) {
          await new Promise((resolve) => { globalThis.__VMQ_RELEASE_COMMAND__ = resolve })
        }
        if (overrides.deferFirstCommand && command.endsWith(overrides.deferFirstCommand) && !globalThis.__VMQ_DEFERRED_ONCE__) {
          globalThis.__VMQ_DEFERRED_ONCE__ = true
          await new Promise((resolve) => { globalThis.__VMQ_RELEASE_FIRST_COMMAND__ = resolve })
        }
        if (overrides.failCommand && command.endsWith(overrides.failCommand)) {
          return { errno: 1, stdout: '', stderr: 'command failed' }
        }
        let statusIndex = -1
        if (command.endsWith(' status')) {
          statusIndex = statusCalls++
          if (overrides.failStatusAfter !== undefined && statusIndex >= overrides.failStatusAfter) {
            return { errno: 1, stdout: '', stderr: 'status refresh failed' }
          }
        }
        if (command.endsWith(' stop')) status = { ...status, supervisor: { ...status.supervisor, pid: -1, listenerPid: -1, desiredState: 'stopped' }, listener: { ...status.listener, running: false, connected: false } }
        if (command.endsWith(' start')) status = { ...status, supervisor: { ...status.supervisor, pid: 4200, listenerPid: 4201, desiredState: 'running' }, listener: { ...status.listener, running: true, connected: true } }
        let data = {}
        if (command.endsWith(' status')) data = overrides.statuses?.[Math.min(statusIndex, overrides.statuses.length - 1)] || overrides.status || status
        if (command.endsWith(' config-get')) data = overrides.config || config
        if (command.endsWith(' logs')) data = { text: '2026-08-16T10:00:00.000Z listener info - ready' }
        if (command.endsWith(' test-connection')) data = { success: true, message: 'success' }
        if (command.endsWith(' test-listener')) data = { healthy: true, message: 'running' }
        if (command.endsWith(' test-parser')) data = { accepted: 3, rejected: 2 }
        return { errno: 0, stdout: JSON.stringify({ ok: true, data }), stderr: '' }
      },
    }
  }, { config, overrides })
}

test('renders the overview immediately and refreshes queue state silently', async ({ page }) => {
  await installMock(page, { delay: 1000 })
  await page.goto('/')
  await expect(page.getByTestId('overview-tab')).toBeVisible()
  await expect(page.getByTestId('service-state')).toContainText('未知')
  await expect(page.locator('body')).not.toContainText('加载中')
  await expect(page.getByTestId('service-state')).toContainText('运行中')
  await expect(page.getByTestId('pending-count')).toHaveText('3')
  await expect(page.getByTestId('failed-count')).toHaveText('1')
})

test('links to the public source repository from the overview', async ({ page }) => {
  await installMock(page)
  await page.goto('/')
  const link = page.getByTestId('source-link')
  await expect(link).toHaveText('开源地址')
  await expect(link).toHaveAttribute('href', 'https://github.com/LeoChen-CoreMind/vmq-ksu-listener')
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
})

test('shows an unhealthy listener even while the framework remains connected', async ({ page }) => {
  await installMock(page, {
    status: {
      supervisor: { pid: 4100, listenerPid: 4101, startedAt: Date.now() - 1000, restartCount: 0, desiredState: 'running' },
      listener: { running: true, healthy: false, frameworkConnected: true, pending: 0, failed: 0 },
      config: { configured: true },
    },
  })
  await page.goto('/')
  await expect(page.getByTestId('listener-health')).toHaveText('未连接')
})

test('updates status every five seconds and uptime locally without rebuilding the app', async ({ page }) => {
  await page.clock.install()
  const startedAt = Date.now() - 65_000
  const status = (pending, failed, restartCount) => ({
    supervisor: { pid: 4100, listenerPid: 4101, startedAt, restartCount, desiredState: 'running' },
    listener: { running: true, pending, failed, lastHeartbeatSuccess: true },
    config: { configured: true },
  })
  await installMock(page, { statuses: [status(1, 0, 2), status(7, 3, 4)] })
  await page.addInitScript(() => {
    const descriptor = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML')
    globalThis.__VMQ_APP_WRITES__ = 0
    Object.defineProperty(Element.prototype, 'innerHTML', {
      configurable: true,
      get: descriptor.get,
      set(value) {
        if (this.id === 'app') globalThis.__VMQ_APP_WRITES__++
        return descriptor.set.call(this, value)
      },
    })
  })
  await page.goto('/')
  await expect(page.getByTestId('pending-count')).toHaveText('1')
  const initialWrites = await page.evaluate(() => globalThis.__VMQ_APP_WRITES__)
  const initialUptime = await page.getByTestId('uptime').textContent()

  await page.clock.fastForward(5100)
  await expect(page.getByTestId('pending-count')).toHaveText('7')
  await expect(page.getByTestId('failed-count')).toHaveText('3')
  await expect(page.getByTestId('restart-count')).toHaveText('4')
  await expect(page.getByTestId('uptime')).not.toHaveText(initialUptime)
  expect(await page.evaluate(() => globalThis.__VMQ_APP_WRITES__)).toBe(initialWrites)
})

test('caches public status without the communication key', async ({ page }) => {
  await installMock(page)
  await page.goto('/')
  await expect(page.getByTestId('service-state')).toContainText('运行中')
  const cached = await page.evaluate(() => sessionStorage.getItem('vmq.lastStatus'))
  expect(cached).not.toContain('secret-key')
  expect(cached).not.toContain('"key"')
})

test('keeps fatal diagnostics available when the initial overview render fails', async ({ page }) => {
  await installMock(page)
  await page.addInitScript(() => {
    const descriptor = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML')
    Object.defineProperty(Element.prototype, 'innerHTML', {
      configurable: true,
      get: descriptor.get,
      set(value) {
        if (this.id === 'app') throw new Error('initial overview render failed')
        return descriptor.set.call(this, value)
      },
    })
  })
  await page.goto('/')
  await expect(page.getByTestId('fatal-error')).toBeVisible()
  await expect(page.getByTestId('fatal-error')).toContainText('initial overview render failed')
  await expect(page.locator('html')).not.toHaveAttribute('data-vmq-ready', 'true')
})

test('renders stopped and command failure states', async ({ page }) => {
  const status = {
    supervisor: { pid: -1, listenerPid: -1, restartCount: 0, desiredState: 'stopped' },
    listener: { running: false, pending: 0, failed: 0 },
    config: { configured: true },
  }
  await installMock(page, { status, failCommand: ' restart' })
  await page.goto('/')
  await expect(page.getByTestId('service-state')).toContainText('已停止')
  await page.getByRole('button', { name: '重启' }).click()
  await expect(page.getByTestId('command-error')).toContainText('command failed')
})

test('renders invalid configuration from the command bridge', async ({ page }) => {
  await installMock(page, { failCommand: ' config-get' })
  await page.goto('/')
  await expect(page.getByTestId('command-error')).toContainText('command failed')
  await expect(page.getByTestId('service-state')).toContainText('运行中')
})

test('masks key, validates config, and writes secret only through encoded exec environment', async ({ page }) => {
  await installMock(page)
  await page.goto('/')
  await page.getByRole('button', { name: '配置' }).click()
  const key = page.getByLabel('通信密钥')
  await expect(key).toHaveAttribute('type', 'password')
  await expect(key).toHaveValue('secret-key')
  await page.getByRole('button', { name: '显示密钥' }).click()
  await expect(key).toHaveAttribute('type', 'text')
  await page.getByRole('button', { name: '隐藏密钥' }).click()
  await key.fill('')
  await page.getByLabel('服务地址').fill('not-a-url')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect(page.getByTestId('config-error')).toBeVisible()
  await page.getByLabel('服务地址').fill('https://vpay.example')
  await key.fill('new-secret')
  await page.getByRole('button', { name: '保存配置' }).click()
  const calls = await page.evaluate(() => globalThis.__VMQ_CALLS__)
  const write = calls.find((call) => call.command.endsWith(' config-set'))
  expect(write.command).not.toContain('new-secret')
  const decoded = await page.evaluate((encoded) => JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded), (char) => char.charCodeAt(0)))), write.options.env.VMQ_CONFIG_B64)
  expect(decoded.key).toBe('new-secret')
  expect(write.options.env.VMQ_CONFIG_JSON).toBeUndefined()
  expect(calls.some((call) => call.command.endsWith(' restart'))).toBeTruthy()
  await page.getByRole('button', { name: '概览' }).click()
  await page.getByRole('button', { name: '配置' }).click()
  await expect(page.getByLabel('通信密钥')).toHaveValue('new-secret')
})

test('restores every saved configuration field after reopening the page', async ({ page }) => {
  const saved = {
    ...config,
    baseUrl: 'https://saved.example/base',
    key: 'saved-secret',
    wechatEnabled: false,
    wecomEnabled: true,
    alipayEnabled: false,
    heartbeatSeconds: 75,
    connectTimeoutSeconds: 12,
    readTimeoutSeconds: 34,
    maxRetries: 5,
    logLevel: 'debug',
  }
  await installMock(page, { config: saved })

  for (let attempt = 0; attempt < 2; attempt++) {
    await page.goto('/')
    await page.getByRole('button', { name: '配置' }).click()
    await expect(page.getByLabel('服务地址')).toHaveValue(saved.baseUrl)
    await expect(page.getByLabel('通信密钥')).toHaveValue(saved.key)
    await expect(page.getByLabel('通信密钥')).toHaveAttribute('type', 'password')
    await expect(page.locator('[name="wechatEnabled"]')).not.toBeChecked()
    await expect(page.locator('[name="wecomEnabled"]')).toBeChecked()
    await expect(page.locator('[name="alipayEnabled"]')).not.toBeChecked()
    await expect(page.locator('[name="heartbeatSeconds"]')).toHaveValue('75')
    await expect(page.locator('[name="connectTimeoutSeconds"]')).toHaveValue('12')
    await expect(page.locator('[name="readTimeoutSeconds"]')).toHaveValue('34')
    await expect(page.locator('[name="maxRetries"]')).toHaveValue('5')
    await expect(page.locator('[name="logLevel"]')).toHaveValue('debug')
  }
})

test('opens the system camera fallback when live camera permission is denied', async ({ page }) => {
  await installMock(page)
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => { throw new DOMException('Permission denied', 'NotAllowedError') } },
    })
    const originalClick = HTMLInputElement.prototype.click
    HTMLInputElement.prototype.click = function click() {
      if (this.id === 'camera-file') {
        globalThis.__VMQ_SYSTEM_CAMERA_OPENED__ = this.getAttribute('capture')
        return
      }
      return originalClick.call(this)
    }
  })
  await page.goto('/')
  await page.getByRole('button', { name: '配置' }).click()
  await page.getByRole('button', { name: '相机扫码' }).click()
  await expect.poll(() => page.evaluate(() => globalThis.__VMQ_SYSTEM_CAMERA_OPENED__)).toBe('environment')
  await expect(page.getByTestId('config-error')).toContainText('已切换到系统相机拍照')
})

test('saves configuration without restarting an intentionally stopped service', async ({ page }) => {
  const status = {
    supervisor: { pid: -1, listenerPid: -1, restartCount: 0, desiredState: 'stopped' },
    listener: { running: false, pending: 0, failed: 0 },
  }
  await installMock(page, { status })
  await page.goto('/')
  await page.getByRole('button', { name: '配置' }).click()
  await page.getByLabel('通信密钥').fill('secret-for-save')
  await page.getByRole('button', { name: '保存配置' }).click()
  const commands = await page.evaluate(() => globalThis.__VMQ_CALLS__.map((call) => call.command))
  expect(commands.some((command) => command.endsWith(' config-set'))).toBeTruthy()
  expect(commands.some((command) => command.endsWith(' restart'))).toBeFalsy()
})

test('reports a saved configuration separately from a failed restart', async ({ page }) => {
  await installMock(page, { failCommand: ' restart' })
  await page.goto('/')
  await page.getByRole('button', { name: '配置' }).click()
  await page.getByLabel('通信密钥').fill('secret-for-restart-failure')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect(page.getByTestId('command-result')).toContainText('配置已保存')
  await expect(page.getByTestId('config-error')).toContainText('服务重启失败')
  const commands = await page.evaluate(() => globalThis.__VMQ_CALLS__.map((call) => call.command))
  expect(commands.findIndex((command) => command.endsWith(' config-set'))).toBeLessThan(commands.findIndex((command) => command.endsWith(' restart')))
})

test('switches tabs without reloading configuration and loads logs lazily', async ({ page }) => {
  await installMock(page)
  await page.goto('/')
  await expect(page.getByTestId('overview-tab')).toBeVisible()
  const before = await page.evaluate(() => globalThis.__VMQ_CALLS__.map((call) => call.command))
  await page.getByRole('button', { name: '配置' }).click()
  await expect(page.getByTestId('config-tab')).toBeVisible()
  await page.getByRole('button', { name: '日志' }).click()
  await expect(page.getByTestId('logs-tab')).toBeVisible()
  await page.getByRole('button', { name: '概览' }).click()
  await expect(page.getByTestId('overview-tab')).toBeVisible()
  const after = await page.evaluate(() => globalThis.__VMQ_CALLS__.map((call) => call.command))
  expect(after.filter((command) => command.endsWith(' config-get'))).toEqual(before.filter((command) => command.endsWith(' config-get')))
  expect(after.filter((command) => command.endsWith(' logs'))).toHaveLength(1)
})

test('runs all operational controls and displays health checks', async ({ page }) => {
  await installMock(page)
  await page.goto('/')
  const service = page.getByTestId('service-toggle')
  await service.click()
  await service.check()
  await page.getByRole('button', { name: '重启服务' }).click()
  await page.getByTestId('heartbeat-check').click()
  await expect(page.getByTestId('command-result')).toContainText('连接测试成功')
  await page.getByTestId('listener-check').click()
  await expect(page.getByTestId('command-result')).toContainText('通知监听正常')
  await expect(page.getByTestId('listener-health')).toHaveText('已连接')
  await page.getByRole('button', { name: '日志' }).click()
  await page.getByRole('button', { name: '刷新日志' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '清空日志' }).click()
  const commands = await page.evaluate(() => globalThis.__VMQ_CALLS__.map((call) => call.command))
  for (const command of ['start', 'stop', 'restart', 'test-connection', 'test-listener', 'logs', 'clear-logs']) {
    expect(commands.some((value) => value.endsWith(` ${command}`))).toBeTruthy()
  }
})

test('keeps navigation responsive and deduplicates a deferred restart', async ({ page }) => {
  await installMock(page, { deferCommand: ' restart' })
  await page.goto('/')
  const restart = page.getByRole('button', { name: '重启服务' })
  await restart.click()
  await restart.click()
  await page.getByRole('button', { name: '配置' }).click()
  await expect(page.getByTestId('config-tab')).toBeVisible()
  const restartCalls = await page.evaluate(() => globalThis.__VMQ_CALLS__.filter((call) => call.command.endsWith(' restart')).length)
  expect(restartCalls).toBe(1)
  await page.evaluate(() => globalThis.__VMQ_RELEASE_COMMAND__())
  await expect(page.getByTestId('command-result')).toContainText('重启命令已发送')
})

test('deduplicates the restart shared by configuration save and the overview action', async ({ page }) => {
  await installMock(page, { deferCommand: ' restart' })
  await page.goto('/')
  await page.getByRole('button', { name: '配置' }).click()
  await page.getByLabel('通信密钥').fill('secret-for-shared-restart')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect.poll(() => page.evaluate(() => globalThis.__VMQ_CALLS__.filter((call) => call.command.endsWith(' restart')).length)).toBe(1)
  await page.getByRole('button', { name: '概览' }).click()
  await page.getByRole('button', { name: '重启服务' }).click()
  expect(await page.evaluate(() => globalThis.__VMQ_CALLS__.filter((call) => call.command.endsWith(' restart')).length)).toBe(1)
  await page.evaluate(() => globalThis.__VMQ_RELEASE_COMMAND__())
})

test('queues a post-save restart behind a restart that began before config-set', async ({ page }) => {
  await installMock(page, { deferFirstCommand: ' restart' })
  await page.goto('/')
  await page.getByRole('button', { name: '重启服务' }).click()
  await expect.poll(() => page.evaluate(() => Boolean(globalThis.__VMQ_RELEASE_FIRST_COMMAND__))).toBe(true)
  await page.getByRole('button', { name: '配置' }).click()
  await page.getByLabel('通信密钥').fill('secret-after-running-restart')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect.poll(() => page.evaluate(() => globalThis.__VMQ_CALLS__.some((call) => call.command.endsWith(' config-set')))).toBe(true)
  expect(await page.evaluate(() => globalThis.__VMQ_CALLS__.filter((call) => call.command.endsWith(' restart')).length)).toBe(1)
  await page.evaluate(() => globalThis.__VMQ_RELEASE_FIRST_COMMAND__())
  await expect.poll(() => page.evaluate(() => globalThis.__VMQ_CALLS__.filter((call) => call.command.endsWith(' restart')).length)).toBe(2)
})

test('does not let an older status response undo an optimistic service switch', async ({ page }) => {
  await installMock(page, { deferCommand: ' status' })
  await page.goto('/')
  const service = page.getByTestId('service-toggle')
  await expect(service).toBeChecked()
  await service.click()
  await expect(service).not.toBeChecked()
  await page.evaluate(() => globalThis.__VMQ_RELEASE_COMMAND__())
  await expect(service).not.toBeChecked()
})

test('keeps the successful service intent when a status refresh fails', async ({ page }) => {
  await installMock(page, { failStatusAfter: 1 })
  await page.goto('/')
  const service = page.getByTestId('service-toggle')
  await expect(service).toBeChecked()
  await service.click()
  await expect(page.getByTestId('command-error')).toContainText('status refresh failed')
  await expect(page.getByTestId('service-toggle')).not.toBeChecked()
})

test('reverts the service switch when its control command fails', async ({ page }) => {
  await installMock(page, { failCommand: ' stop' })
  await page.goto('/')
  const service = page.getByTestId('service-toggle')
  await expect(service).toBeChecked()
  await service.click()
  await expect(page.getByTestId('command-error')).toContainText('command failed')
  await expect(page.getByTestId('service-toggle')).toBeChecked()
})

for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
  test(`has no overflow at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await installMock(page)
    await page.goto('/')
    await expect(page.getByTestId('service-state')).toContainText('运行中')
    const overflowing = await page.evaluate(() => [...document.querySelectorAll('body *')].map((element) => {
      const rect = element.getBoundingClientRect()
      return { tag: element.tagName, className: element.className?.baseVal || element.className || '', left: Math.round(rect.left), right: Math.round(rect.right), width: Math.round(rect.width) }
    }).filter((item) => item.right > window.innerWidth + 1 || item.left < -1))
    expect(overflowing).toEqual([])
    const layout = await page.evaluate(() => {
      const nav = document.querySelector('.bottom-nav').getBoundingClientRect()
      const switchNode = document.querySelector('.master-switch')
      const switchParent = switchNode.parentElement.getBoundingClientRect()
      const switchRect = switchNode.getBoundingClientRect()
      const overlaps = [...document.querySelectorAll('main button, main input, main select, main pre, main [tabindex]')]
        .filter((element) => {
          const style = getComputedStyle(element)
          const rect = element.getBoundingClientRect()
          return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0 && rect.bottom > nav.top + 1 && rect.top < nav.bottom - 1
        })
        .map((element) => element.outerHTML.slice(0, 120))
      return {
        overlaps,
        switchContained: switchRect.left >= switchParent.left - 1 && switchRect.right <= switchParent.right + 1 && switchRect.top >= switchParent.top - 1 && switchRect.bottom <= switchParent.bottom + 1,
      }
    })
    expect(layout.overlaps).toEqual([])
    expect(layout.switchContained).toBeTruthy()
    await page.screenshot({ path: `test-results/webui-${viewport.width}x${viewport.height}.png`, fullPage: true })
  })
}

test('fatal error view has readable text contrast', async ({ page }) => {
  await page.route('**/src/main.js', (route) => route.abort())
  await page.goto('/')
  await expect(page.getByTestId('fatal-error')).toBeVisible()
  const contrast = await page.getByTestId('fatal-error').evaluate((element) => {
    const parse = (value) => value.match(/[\d.]+/g).slice(0, 3).map(Number)
    const luminance = (rgb) => {
      const channels = rgb.map((value) => {
        const channel = value / 255
        return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
      })
      return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
    }
    let backgroundNode = element
    while (backgroundNode && getComputedStyle(backgroundNode).backgroundColor === 'rgba(0, 0, 0, 0)') backgroundNode = backgroundNode.parentElement
    const foreground = luminance(parse(getComputedStyle(element).color))
    const background = luminance(parse(getComputedStyle(backgroundNode || document.body).backgroundColor))
    return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
  })
  expect(contrast).toBeGreaterThanOrEqual(4.5)
})
