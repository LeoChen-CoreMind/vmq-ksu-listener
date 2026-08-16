# 发布流程

## 1. 准备版本

1. 更新 `module/module.prop` 的 `version` 和 `versionCode`。
2. 更新 `CHANGELOG.md` 和对应的 `docs/releases/` 发布说明。
3. 确认目标仍为 `arm64-v8a`、Android API 35 和 KernelSU。

## 2. 本地验证

```powershell
cd web
npm ci
npx playwright install chromium
npm test
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-java.ps1 -RunJvmTests
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/assert-release-version.ps1 -Tag v1.0.0
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-static.ps1 -Zip dist/vmq-ksu-v1.0.0-arm64-api35.zip
```

需要设备验收时，再运行 `verify-module.ps1` 和 `install-device.ps1`，并显式传入 `-Serial <serial>`。

## 3. 内容检查

- `git status --short` 只允许预期源码或文档变更。
- `git diff --check` 无错误。
- 仓库不包含通信密钥、支付数据、运行日志、设备序列号、本机绝对路径或生成物。
- ZIP 根目录包含 `LICENSE`、`module.prop` 和所有模块文件，不含父目录前缀。

## 4. 标签与 Workflow

合并到 `main` 并确认 CI 成功后，创建与 `module.prop` 一致的带注释标签：

```powershell
git tag -a v1.0.0 -m "V免签通知监听 v1.0.0"
git push origin v1.0.0
```

Release Workflow 会再次校验标签版本、运行测试和打包，然后创建正式 GitHub Release。

## 5. Release 附件

Release 只上传：

- `vmq-ksu-v1.0.0-arm64-api35.zip`
- `vmq-ksu-v1.0.0-arm64-api35.zip.sha256`

GitHub 自动生成的源码归档无需手工上传。

下载附件后执行：

```powershell
$expected = ((Get-Content -Raw vmq-ksu-v1.0.0-arm64-api35.zip.sha256).Trim() -split '\s+')[0]
$actual = (Get-FileHash -Algorithm SHA256 vmq-ksu-v1.0.0-arm64-api35.zip).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'SHA256 mismatch' }
```
