import { expect, test } from '@playwright/test'

async function parse(page, value) {
  await page.goto('/')
  return page.evaluate(async (raw) => {
    const { parseConfigValue } = await import('/src/qr.js')
    try { return { value: parseConfigValue(raw) } } catch (error) { return { error: error.message } }
  }, value)
}

test('parses legacy host, port, base path, and explicit schemes', async ({ page }) => {
  await expect((await parse(page, 'example.com:8080/base/key')).value).toEqual({ baseUrl: 'https://example.com:8080/base', key: 'key' })
  await expect((await parse(page, 'https://example.com/key')).value).toEqual({ baseUrl: 'https://example.com', key: 'key' })
  await expect((await parse(page, 'http://example.com/key')).value).toEqual({ baseUrl: 'http://example.com', key: 'key' })
})

test('rejects malformed QR values', async ({ page }) => {
  for (const value of ['', 'example.com', 'javascript://host/key', 'https://user:pass@example.com/key', 'https://example.com/key?x=1', 'https://example.com/key#part']) {
    expect((await parse(page, value)).error).toBeTruthy()
  }
})

test('decoder is local and releases image object URLs', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const calls = []
    const { QrScanner } = await import('/src/qr.js')
    const scanner = new QrScanner({ decodeFromImageUrl: async (url) => { calls.push(url); return { getText: () => 'example.com/key' } } })
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    URL.createObjectURL = () => 'blob:local'
    URL.revokeObjectURL = (url) => calls.push(`revoked:${url}`)
    try {
      const text = await scanner.decodeImage(new Blob(['qr']))
      return { text, calls }
    } finally {
      URL.createObjectURL = originalCreate
      URL.revokeObjectURL = originalRevoke
    }
  })
  expect(result.text).toBe('example.com/key')
  expect(result.calls).toEqual(['blob:local', 'revoked:blob:local'])
})
