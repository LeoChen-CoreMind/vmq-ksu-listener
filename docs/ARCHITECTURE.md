# 架构说明

## 运行组件

- `service.sh`：等待 Android 启动完成，保存期望运行状态并启动 supervisor。
- `vmqd`：native supervisor，管理 Java 监听进程、重启计数、PID 和模块描述。
- `app_process64` / `com.vmq.ksu.Main`：加载模块 DEX，注册通知监听、解析通知、维护心跳和投递队列。
- `vmqctl` / `com.vmq.ksu.ControlMain`：为 WebUI、ADB 和模块操作提供 JSON 控制接口。
- KernelSU WebUI：呈现状态、编辑配置、执行后台任务并读取受限日志。

## 支付通知数据流

```text
service.sh
  -> vmqd
  -> app_process64 / com.vmq.ksu.Main
  -> Android notification listener
  -> payment parser and deduplication
  -> retry queue
  -> V免签 /appPush
```

监听进程同时按配置向 `/appHeart` 发送心跳。支付事件先经过来源识别、金额解析和去重，再进入投递流程；失败事件保留在队列中并按最大重试次数处理。

## 控制数据流

```text
KernelSU WebUI
  -> KernelSU exec bridge
  -> module/bin/vmqctl
  -> com.vmq.ksu.ControlMain
  -> config, state, logs, supervisor control
```

控制命令返回稳定 JSON 结构。配置写入使用 `VMQ_CONFIG_B64` 环境变量传递，避免通信密钥出现在命令参数中。WebUI 状态缓存仅保留不含密钥的公开状态。

## 持久化

运行数据位于 `/data/adb/vmq_ksu/`：

- `config.json`：连接和监听配置，权限为 `0600`。
- `desired_state`：用户期望的运行状态。
- `state.json`：supervisor 与监听状态快照。
- `queue.jsonl`：待重试支付事件。
- `dedup.json`：已处理事件去重数据。
- `vmq.log`：运行日志。

运行目录权限为 `0700`，敏感文件权限为 `0600`。日志输出会对通信密钥进行脱敏，并限制读取行数和字节数。

## 生命周期

KernelSU 在开机时执行 `service.sh`。supervisor 根据 `desired_state` 决定是否启动监听进程，并在子进程异常退出时记录重启次数后恢复。`vmqctl start|stop|restart` 只操作已记录且命令行身份匹配的 PID。卸载脚本停止这些进程、恢复旧通知监听组件状态并清理运行目录。
