# V免签通知监听

V免签通知监听是一个面向 KernelSU 的 Android 通知监听模块。它不依赖常驻 APK，通过系统通知监听接口识别微信、企业微信和支付宝收款通知，并向 V免签服务发送心跳与支付回调。

## 功能

- 监听微信、企业微信和支付宝收款通知。
- 向 V免签 `/appHeart` 发送心跳，向 `/appPush` 投递支付事件。
- 失败事件进入本地队列并按配置重试，支持去重与失败记录。
- KernelSU WebUI 提供服务状态、动态指标、配置、检测、重启和日志管理。
- 服务期望状态持久化；手动停止后，设备重启仍保持停止。
- 日志在 WebUI 中最新记录置顶，最多显示 200 行或 32 KiB。

## 兼容性

当前正式版只支持以下环境：

- `arm64-v8a`
- Android API 35
- KernelSU

其他 ABI、Android 版本和 Root 方案未纳入支持范围。

## 安装

1. 从 [最新 Release](https://github.com/LeoChen-CoreMind/vmq-ksu-listener/releases/latest) 下载 `vmq-ksu-v1.0.0-arm64-api35.zip` 和同名 `.sha256` 文件。
2. 校验 ZIP 的 SHA256 与校验文件一致。
3. 在 KernelSU 管理器中选择“从本地安装”，刷入 ZIP。
4. 重启设备，在模块列表中打开“V免签通知监听”的 WebUI。

也可以通过仓库脚本安装已构建的本地包：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-device.ps1 -Serial <serial> -Install
adb -s <serial> reboot
```

## WebUI 配置

在“配置”页填写 V免签服务地址和通信密钥，再选择需要监听的支付来源。配置支持手动输入、相机扫码、拍照扫码和导入二维码。

保存配置时，若服务正在运行，模块会在后台重启监听服务；若服务已停止，则只保存配置。通信密钥通过 KernelSU 执行环境传递，不写入命令参数，WebUI 状态缓存也不会保存密钥。

## 模块状态

“概览”页每 5 秒静默更新服务、心跳、监听、重启次数、待发送和失败队列；运行时长在浏览器中每秒更新。检测心跳、检测监听、重启和服务开关均在后台运行，不阻塞页面切换。

常用诊断命令：

```powershell
adb -s <serial> shell su -c '/data/adb/modules/vmq_ksu/bin/vmqctl status'
adb -s <serial> shell su -c '/data/adb/modules/vmq_ksu/bin/vmqctl test-connection'
adb -s <serial> shell su -c '/data/adb/modules/vmq_ksu/bin/vmqctl test-listener'
adb -s <serial> shell su -c '/data/adb/modules/vmq_ksu/bin/vmqctl logs'
```

服务控制命令为 `vmqctl start`、`vmqctl stop` 和 `vmqctl restart`。模块只操作状态文件记录且命令行匹配的进程 PID，不使用宽泛进程匹配。

## 日志

“日志”页支持手动刷新、自动刷新和确认后清空。自动刷新设置保存在浏览器本地，重新进入页面后继续生效；新日志显示在顶部。模块日志及轮转文件分别限制为 256 KiB，并清理七天前的轮转日志。

日志、配置、状态和队列位于 `/data/adb/vmq_ksu/`。提交问题前请先删除通信密钥、支付数据和其他隐私信息。

## 从源码构建

环境要求：Node.js 22、JDK 17、Android SDK 35、Android build-tools 35.0.1、Android NDK 27.3.13750724 和 PowerShell。

```powershell
cd web
npm ci
npx playwright install chromium
npm test
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package.ps1
```

构建产物：

- `dist/vmq-ksu-v1.0.0-arm64-api35.zip`
- `dist/vmq-ksu-v1.0.0-arm64-api35.zip.sha256`

打包过程只执行无设备静态验证。静态验证也可以单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-static.ps1 -Zip dist/vmq-ksu-v1.0.0-arm64-api35.zip
```

## 设备验证

真机验证要求设备已通过 ADB 连接，并满足 Android API 35、`arm64-v8a` 和 KernelSU 条件。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-module.ps1 -Serial <serial> -Zip dist/vmq-ksu-v1.0.0-arm64-api35.zip
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-device.ps1 -Serial <serial> -Verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-device.ps1 -Serial <serial> -Lifecycle
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-device.ps1 -Serial <serial> -RebootVerify
```

## 卸载

可以在 KernelSU 管理器中卸载模块，或执行回滚脚本后重启：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-device.ps1 -Serial <serial> -Rollback
adb -s <serial> reboot
```

卸载脚本会停止模块进程、恢复安装前的旧通知监听组件状态，并删除 `/data/adb/vmq_ksu/`。该目录中的配置、队列和日志删除后无法恢复。

## 常见问题

### 心跳检测失败

确认服务地址可从设备访问、通信密钥与 V免签后台一致，并检查“日志”页中的中文错误信息。再运行 `vmqctl test-connection` 获取单次检测结果。

### 收款后没有回调

确认对应支付来源已启用，通知权限和系统通知没有被关闭，并运行 `vmqctl test-listener` 检查监听状态。待发送或失败队列非零时，查看最近投递信息和日志。

### 模块列表显示服务已停止

`module.prop` 描述会随 supervisor 启动和停止动态更新。重新进入 KernelSU 模块列表后仍显示停止时，请从 WebUI 打开服务开关并检查日志。

## 隐私与安全

模块处理收款通知、通信密钥和支付事件。不要公开 `/data/adb/vmq_ksu/` 的原始内容，不要把通信密钥写入 Issue、截图或日志附件。发现漏洞请按 [安全策略](SECURITY.md) 私下报告。

## 贡献

开发环境、测试和提交要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。运行架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，发布流程见 [docs/RELEASING.md](docs/RELEASING.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。
