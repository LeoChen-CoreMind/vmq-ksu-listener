import { BrowserQRCodeReader } from '@zxing/browser'

const SCHEMES = new Set(['http:', 'https:'])

export function normalizeBaseUrl(raw) {
  let value = String(raw || '').trim()
  if (!value) throw new Error('服务地址不能为空')
  if (!/^[a-z][a-z\d+.-]*:\/\//i.test(value)) value = `https://${value}`
  let url
  try { url = new URL(value) } catch { throw new Error('二维码中的服务地址无效') }
  if (!SCHEMES.has(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) {
    throw new Error('服务地址必须是无账号、查询参数和片段的 HTTP(S) 地址')
  }
  return value.replace(/\/$/, '')
}

export function parseConfigValue(raw) {
  const value = String(raw || '').trim()
  if (!value || value.includes('?') || value.includes('#')) throw new Error('二维码内容无效')
  const separator = value.lastIndexOf('/')
  if (separator < 1 || separator === value.length - 1) throw new Error('二维码格式应为：主机地址/通信密钥')
  const baseUrl = normalizeBaseUrl(value.slice(0, separator))
  const key = value.slice(separator + 1).trim()
  if (!key || key.includes('/')) throw new Error('二维码通信密钥无效')
  return { baseUrl, key }
}

export class QrScanner {
  constructor(reader = new BrowserQRCodeReader()) {
    this.reader = reader
    this.controls = null
    this.video = null
  }

  async start(video, onValue) {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前 WebView 不支持实时相机')
    this.stop()
    this.video = video
    this.controls = await this.reader.decodeFromVideoDevice(undefined, video, (result) => {
      if (result) {
        onValue(result.getText())
        this.stop()
      }
    })
    return { stop: () => this.stop() }
  }

  async decodeImage(file) {
    if (!file) throw new Error('没有选择二维码图片')
    const url = URL.createObjectURL(file)
    try {
      const result = await this.reader.decodeFromImageUrl(url)
      return result.getText()
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  stop() {
    this.controls?.stop?.()
    this.controls = null
    const tracks = this.video?.srcObject?.getTracks?.() || []
    tracks.forEach((track) => track.stop())
    if (this.video) this.video.srcObject = null
    this.video = null
  }
}
