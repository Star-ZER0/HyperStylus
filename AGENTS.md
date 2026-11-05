# HyperStylus 项目协作指南

本文适用于整个项目。应用 ID 与资源命名空间为 `cc.star0.xposed.hyperstylus`，版本号以 `app/build.gradle.kts` 为准。入口、方法签名和执行顺序以源码为准；修改 Hook、配置或生命周期时同步更新 [运行时参考](docs/HOOKS.md)。

## 阅读入口

- [README](README.md)：功能、安装与构建入口。
- [Hook 文档](docs/HOOKS.md)：功能源文件、宿主接口、配置、作用域、生命周期与验证边界。普通源码修改无需重新解包 OTA。

系统适配依据为 `小米平板 6 Max 14 (yudi)` 的 `OS3.0.6.0.VMHCNXM / Android 15`；侧边条依据平板管家 `13.6.2-260922.0.1.pad`。最低安装版本不代表其他 ROM 具备相同接口，静态接口依据也不等于目标设备实测。

## 分层与接口

- `module` 管宿主安装与代际；`config` 管不可变配置；`runtime` 管宿主行为；`model/pen` 管遥测；`storage` 管事务记录；`ui` 管展示与编辑。
- `runtime` 按六类功能归组，共享蓝牙遥测保留 `bluetooth`。测试镜像生产包；新增、移动文件时同步 Hook 文档中的相关路径。
- 修改 Hook 前确认目标宿主、实际 ClassLoader、方法签名和返回类型。ABI 不匹配时记录不可用并放行，不按设备名称、相似方法名或任意 GATT 对象猜测支持。
- 所有 Xposed 拦截，包括插件和动态派生类入口，必须经 `FocusStylusModule.managedHook()` 或基于它的 `after()` / `afterMethods()` 安装。`afterMethods()` 不负责参数与返回值筛选。
- system_server 准入使用框架 `isSystemServer` 标志；应用宿主使用 `HostProcessPolicy` 精确匹配包／进程。辅助进程在公共配置初始化前返回。
- 修改包名、入口或作用域时，同时核对 Manifest、Protocol、Xposed 元数据、R8 与 APK 检查。libxposed API 必须保持 `compileOnly`。

## 配置、输入和资源

- 高频回调只读取 `ConfigBridge.current()` 内存快照；不得同步访问 Provider、磁盘或耗时 Binder。保留线程归属、流身份及 DOWN 时配置快照。
- 仅使用当前配置格式和动作标识；保留缺失字段默认值、合法的全局回退与当前 AtomicFile 事务恢复。应用版本与配置格式版本独立维护。
- 按键、手势与原生轻捏／双击的分应用规则分别维护。防误触 profile 完整替换全局掩码，0 掩码也生效。
- 分别识别模块停止权、原生停止意图、模块合成键与真实物理键；退出、失败、设备移除及关闭功能时完成按键／激光释放。
- 完整映射使用标准虚拟键盘身份；软件 Pro 命令保留真实笔身份。普通热重载保留原生设置 journal，关闭接管或显式移除 system 作用域时按所有权恢复。
- 会话退出先停止新工作、等待在途回调，再逆序释放全部资源；失败不得误报成功。跨代只保存中性数据及真正宿主对象，禁止旧模块模型、ClassLoader、Hooker 或监听器。
- 公共 Application 初始化复用 `HostLifecycle`；焦点客户端 receiver 注销复用 `ReceiverRegistration` 并确认真实注册表已移除。仅关闭模块创建的线程和资源。
- 保持八个状态矢量和 XML 应用图标；模块资源必须通过模块包 Context 加载。保留作者头像与赞赏码原始 JPEG。
- Provider 和广播使用真实 Binder／广播发送者 UID 校验。充电必须使用官方 BLE 会话的新鲜遥测，显示缓存不能作为充电准入依据；原生保护优先。

## 作用域与界面

功能需求按完整配置求并集，只管理六个声明宿主；不得移除未知作用域。框架授权、进程加载、功能安装和热重载成功分别呈现。

作用域变化后，system 需要重启设备，其他宿主需要重启对应进程。移除正在使用的 system 作用域前，必须通过可信请求确认按键释放和原生设置恢复。框架的辅助进程加载记录仍保留，不能伪造成卸载。

UI 沿用 miuix Compose、统一配置校验、自动保存和导航状态；当前主页为“状态／设置／关于”。应用配置保留未安装应用的已编辑规则。

## 构建与验证

使用 JDK 21、SDK 37.2、Build Tools 37.0.0 和项目 Wrapper，不提交 `local.properties` 或签名材料。

```bash
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleRelease :app:lintRelease
```

纯文档修改检查引用和源码对应关系即可。修改运行时需执行相关回归；共享配置、代际、输入注入、CI 或 APK 发布产物变更应执行完整检查。区分源码实现、自动测试与目标设备实测，不用模拟测试证明 HAL、窗口透传或前台应用支持。

Release 默认未签名，需使用 Android SDK 的 `apksigner` 签名后安装；构建成功不等于签名验证。
