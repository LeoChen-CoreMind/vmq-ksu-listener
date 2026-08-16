import { expect, test } from '@playwright/test'

const config = {
  baseUrl: 'https://vpay.example',
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

async function installLogMock(page, { deferLogs = false } = {}) {
  await page.addInitScript(({ config, deferLogs }) => {
    const lines = Array.from({ length: 1000 }, (_, index) => `line-${String(index).padStart(4, '0')} ${'x'.repeat(180)}`)
    globalThis.__VMQ_LOG_CALLS__ = []
    globalThis.__VMQ_DEFER_LOGS__ = deferLogs
    globalThis.__VMQ_KSU_MOCK__ = {
      exec: async (command) => {
        globalThis.__VMQ_LOG_CALLS__.push(command)
        let data = {}
        if (command.endsWith(' status')) {
          data = {
            supervisor: { pid: 4100, listenerPid: 4101, desiredState: 'running' },
            listener: { running: true, pending: 0, failed: 0 },
            config: { configured: true },
          }
        }
        if (command.endsWith(' config-get')) data = config
        if (command.endsWith(' logs')) {
          if (globalThis.__VMQ_DEFER_LOGS__) {
            await new Promise((resolve) => {
              globalThis.__VMQ_RELEASE_LOGS__ = () => {
                globalThis.__VMQ_DEFER_LOGS__ = false
                resolve()
              }
            })
          }
          data = { text: lines.join('\n'), truncated: false }
        }
        return { errno: 0, stdout: JSON.stringify({ ok: true, data }), stderr: '' }
      },
    }
  }, { config, deferLogs })
}

async function logCallCount(page) {
  return page.evaluate(() => globalThis.__VMQ_LOG_CALLS__.filter((command) => command.endsWith(' logs')).length)
}

test('bounds a 1000-line bridge response and replaces the log DOM', async ({ page }) => {
  await installLogMock(page)
  await page.goto('/')
  expect(await logCallCount(page)).toBe(0)

  await page.locator('[data-tab="logs"]').click()
  await expect.poll(() => logCallCount(page)).toBe(1)
  const output = page.locator('#log-output')
  const text = await output.textContent()
  const lines = text.split('\n')
  expect(lines.length).toBeLessThanOrEqual(200)
  expect(new TextEncoder().encode(text).length).toBeLessThanOrEqual(32 * 1024)
  expect(text).toContain('line-0999')
  expect(text).not.toContain('line-0000')
  expect(lines[0]).toContain('line-0999')
  await expect(page.locator('.log-marker')).toBeVisible()

  const before = await output.evaluate((node) => ({ height: node.clientHeight, overflowY: getComputedStyle(node).overflowY }))
  await page.locator('[data-command="logs"]').click()
  await page.locator('[data-command="logs"]').click()
  expect(await page.locator('#log-output').count()).toBe(1)
  const after = await page.locator('#log-output').evaluate((node) => ({ height: node.clientHeight, overflowY: getComputedStyle(node).overflowY }))
  expect(after).toEqual(before)
  expect(after.overflowY).toMatch(/auto|scroll/)
})

test('runs one auto-refresh timer only while the log tab is visible', async ({ page }) => {
  await page.clock.install()
  await installLogMock(page)
  await page.goto('/')
  await page.locator('[data-tab="logs"]').click()
  await expect.poll(() => logCallCount(page)).toBe(1)
  await page.locator('[data-action="auto-log-refresh"]').check()
  await expect.poll(() => logCallCount(page)).toBe(2)

  await page.clock.fastForward(5100)
  await expect.poll(() => logCallCount(page)).toBe(3)
  await page.clock.fastForward(5100)
  await expect.poll(() => logCallCount(page)).toBe(4)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.clock.fastForward(10_200)
  expect(await logCallCount(page)).toBe(4)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => logCallCount(page)).toBe(5)
  await page.locator('[data-tab="overview"]').click()
  await page.clock.fastForward(10_200)
  expect(await logCallCount(page)).toBe(5)
})

test('confirms clearing and clears both the rendered text and truncation marker', async ({ page }) => {
  await installLogMock(page)
  await page.goto('/')
  await page.locator('[data-tab="logs"]').click()
  await expect(page.locator('.log-marker')).toBeVisible()
  await page.evaluate(() => { window.confirm = () => true })
  await page.locator('[data-command="clear-logs"]').click()
  await expect(page.locator('#log-output')).toContainText('暂无日志')
  await expect(page.locator('.log-marker')).toBeHidden()
  const commands = await page.evaluate(() => globalThis.__VMQ_LOG_CALLS__)
  expect(commands.some((command) => command.endsWith(' clear-logs'))).toBeTruthy()
})

test('restores the automatic log preference after reloading the WebUI', async ({ page }) => {
  await installLogMock(page)
  await page.goto('/')
  await page.locator('[data-tab="logs"]').click()
  await page.locator('[data-action="auto-log-refresh"]').check()
  await expect.poll(() => logCallCount(page)).toBe(2)

  await page.reload()
  await page.locator('[data-tab="logs"]').click()
  await expect(page.locator('[data-action="auto-log-refresh"]')).toBeChecked()
  await expect.poll(() => logCallCount(page)).toBe(1)
})

test('does not restore an in-flight log response after clearing', async ({ page }) => {
  await installLogMock(page, { deferLogs: true })
  await page.goto('/')
  await page.locator('[data-tab="logs"]').click()
  await expect.poll(() => logCallCount(page)).toBe(1)
  await page.evaluate(() => { window.confirm = () => true })
  await page.locator('[data-command="clear-logs"]').click()
  await expect(page.locator('#log-output')).toContainText('暂无日志')
  await page.evaluate(() => globalThis.__VMQ_RELEASE_LOGS__())
  await page.waitForTimeout(50)
  await expect(page.locator('#log-output')).toContainText('暂无日志')
  await expect(page.locator('#log-output')).not.toContainText('line-0999')
})
