# 贡献指南

感谢参与 V免签通知监听开发。提交代码前，请先创建 Issue 说明问题或变更目标，避免重复实现。

## 开发环境

- Node.js 22
- JDK 17
- Android SDK 35
- Android build-tools 35.0.1
- Android NDK 27.3.13750724
- PowerShell

设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK，并设置 `ANDROID_NDK_HOME` 指向指定 NDK 目录。

## 本地验证

```powershell
cd web
npm ci
npx playwright install chromium
npm test
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-java.ps1 -RunJvmTests
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-static.ps1 -Zip dist/vmq-ksu-v1.0.0-arm64-api35.zip
```

真机验证必须显式传入设备序列号：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-module.ps1 -Serial <serial> -Zip dist/vmq-ksu-v1.0.0-arm64-api35.zip
```

## 提交要求

- 保持命令、JSON 字段、包名和 `VMQ_*` 诊断标识兼容。
- 不提交通信密钥、支付数据、运行日志、设备序列号或本机绝对路径。
- 不提交 `build/`、`dist/`、`module/bin/vmqd`、`module/dex/`、`module/webroot/` 和 `web/node_modules/`。
- 新行为应包含聚焦测试；WebUI 变更需检查移动端和桌面端布局。
- Pull Request 必须通过 GitHub Actions CI。

提交信息建议使用清晰的命令式描述，例如 `fix: handle listener restart failure`。
