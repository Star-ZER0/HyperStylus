> [!WARNING]
> **AI Slop Warning**
>
> 本项目包含 AI 生成的代码。因个人原因，作者未能充分审查项目代码，可能存在尚未发现的问题，请谨慎使用。

# HyperStylus

[![Android CI](https://github.com/Star-ZER0/HyperStylus/actions/workflows/ci.yml/badge.svg)](https://github.com/Star-ZER0/HyperStylus/actions/workflows/ci.yml)

面向小米 HyperOS 触控笔的 Android / libxposed 增强模块。

[Hook 文档](docs/HOOKS.md) · [构建](#构建) · [问题反馈](https://github.com/Star-ZER0/HyperStylus/issues)

## 功能

| 功能 | 支持内容 |
| --- | --- |
| 电量与信号 | 状态栏、控制中心电量显示，充电状态、RSSI 信号及唤醒刷新 |
| 充电管理 | 充电上下限、独立充电／吸附通知、本次停止／开始／充满操作 |
| 按键与映射 | 三键独立启停、单击与长按动作、完整键盘映射、分应用规则 |
| 触控笔防误触 | 返回、系统栏、主页／最近任务、窗口控制器、角落、超级岛、分屏操作条、侧边条 |
| 原生手势 | 轻捏与双击独立接管、分应用动作、关闭后的原生设置恢复 |
| 设置与状态 | Pro 设置入口、自动保存、按功能申请作用域、框架与进程状态、API 102 热重载 |

## 使用条件

- 最低 Android 14（API 34），需要已配置的、支持 libxposed API 102 的模块框架。
- 系统适配依据为 `小米平板 6 Max 14 (yudi)` `OS3.0.6.0.VMHCNXM / Android 15`；侧边条依据平板管家 `13.6.2-260922.0.1.pad`。最低安装版本不代表每个 ROM 都具备相同接口。
- 软件轻捏、双击和滑动动作的效果取决于前台应用的实现。动作链路与宿主接口见 [Hook 文档](docs/HOOKS.md)。

## 安装与配置

1. 安装已签名的 APK；可自行构建 Debug 包，或在成功的 [CI 运行](https://github.com/Star-ZER0/HyperStylus/actions/workflows/ci.yml) 中下载 Debug 产物。
2. 在模块框架中启用 HyperStylus，打开应用，按功能需求批准作用域。
3. `system` 作用域变化后重启设备，其他作用域变化后重启对应宿主进程。
4. 在“状态”页核对框架连接、实际作用域、宿主进程和加载版本，再到“设置”启用所需功能。

默认开启电量／信号显示、三个按键和 Pro 设置入口；充电限制、两类通知、防误触和原生手势接管默认关闭。完整默认值、作用域表和排查方法见 [Hook 文档](docs/HOOKS.md)。

CI 的 Release 包**未签名**，需签名后才能安装。自动测试验证模块逻辑，目标设备行为仍需单独验证。

## 构建

准备 JDK 21、Android SDK 37.2 和 Build Tools 37.0.0。通过 `ANDROID_HOME` 或本地 `local.properties` 指定 SDK 后执行：

```bash
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleRelease :app:lintRelease
```

Windows 使用 `./gradlew.bat` 执行相同任务。依赖版本在 `gradle/libs.versions.toml` 中维护；CI 执行上述构建、测试与 Lint 任务，并上传 APK 和报告。Release APK 位于 `app/build/outputs/apk/release/`，需使用 Android SDK 的 `apksigner` 签名后安装。

## 项目结构

```text
.github/       GitHub Actions 与协作模板
app/src/main/  设置应用、模块运行时、资源与 Xposed 元数据
app/src/test/  JUnit / Robolectric 回归和模拟宿主
gradle/        Wrapper、依赖版本目录与运行时约束
docs/HOOKS.md  Hook、配置、宿主与生命周期说明
```

运行时按 `battery`、`charge`、`keys`、`gesture`、`nativegesture`、`prosettings` 归组，共享遥测放在 `bluetooth`。各功能的源文件和调用链见 [Hook 文档](docs/HOOKS.md)。


## 参与和联系

提交问题时请提供设备、ROM、框架、触控笔、应用版本与复现步骤；代码修改应遵循 [项目协作约定](AGENTS.md)。

作者：**星澪Star_ZER0** · [个人主页](https://star0.cc) · [GitHub](https://github.com/Star-ZER0) · [QQ 群](https://qm.qq.com/q/jVEd9nftWE)

项目使用 [libxposed](https://github.com/libxposed)、[miuix](https://github.com/compose-miuix-ui/miuix) 和 AndroidX Compose。仓库目前未声明项目许可证，依赖的授权以各自项目为准。
