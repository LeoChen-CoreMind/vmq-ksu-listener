import { expect, test } from '@playwright/test'

async function callBridge(page, mock, expression) {
  await page.addInitScript((implementation) => {
    globalThis.__VMQ_KSU_MOCK__ = { exec: globalThis.eval(`(${implementation})`) }
  }, mock.toString())
  await page.goto('/')
  return page.evaluate(expression)
}

test('bridge times out and classifies invalid responses', async ({ page }) => {
  const timeout = await callBridge(page, () => new Promise(() => {}), async () => {
    const { runControl } = await import('/src/bridge.js')
    try { await runControl('status', { timeoutMs: 50 }) } catch (error) { return { kind: error.kind, message: error.message } }
  })
  expect(timeout.kind).toBe('timeout')
  expect(timeout.message).toContain('超时')

  const invalid = await callBridge(page, async () => ({ errno: 0, stdout: '{', stderr: '' }), async () => {
    const { runControl } = await import('/src/bridge.js')
    try { await runControl('status') } catch (error) { return { kind: error.kind, message: error.message } }
  })
  expect(invalid.kind).toBe('protocol')

  const failed = await callBridge(page, async () => ({ errno: 7, stdout: '', stderr: '' }), async () => {
    const { runControl } = await import('/src/bridge.js')
    try { await runControl('status') } catch (error) { return { kind: error.kind, message: error.message } }
  })
  expect(failed).toEqual({ kind: 'exec', message: '命令执行失败：7' })
})

test('config errors redact the communication key', async ({ page }) => {
  const result = await callBridge(page, async () => ({ errno: 1, stdout: '', stderr: 'bad secret-value' }), async () => {
    const { writeConfig } = await import('/src/bridge.js')
    try { await writeConfig({ key: 'secret-value' }) } catch (error) { return { kind: error.kind, message: error.message } }
  })
  expect(result.kind).toBe('exec')
  expect(result.message).toContain('[REDACTED]')
  expect(result.message).not.toContain('secret-value')
})

test('config uses a shell-safe base64 environment value for buggy WebUI hosts', async ({ page }) => {
  const result = await callBridge(page, async (_command, options) => ({
    errno: 0,
    stdout: JSON.stringify({ ok: true, data: options }),
    stderr: '',
  }), async () => {
    const { writeConfig } = await import('/src/bridge.js')
    return writeConfig({ baseUrl: 'https://pay.example/路径', key: "quote'& value" })
  })

  expect(Object.keys(result.env)).toEqual(['VMQ_CONFIG_B64'])
  expect(result.env.VMQ_CONFIG_B64).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
  const decoded = Buffer.from(result.env.VMQ_CONFIG_B64, 'base64').toString('utf8')
  expect(JSON.parse(decoded)).toEqual({ baseUrl: 'https://pay.example/路径', key: "quote'& value" })
})

test('static boot shell reports a missing module script', async ({ page }) => {
  await page.route('**/src/main.js', (route) => route.abort())
  await page.goto('/')
  await expect(page.getByTestId('fatal-error')).toBeVisible({ timeout: 6000 })
  await expect(page.getByRole('button', { name: '重新加载' })).toBeVisible()
})
