# Hook 与宿主运行时参考

本文描述 HyperStylus 的当前实现。开发约定见 [AGENTS.md](../AGENTS.md)，版本号以 `app/build.gradle.kts` 为准。具体方法签名、常量和执行顺序以源码为准。

系统适配依据为 `小米平板 6 Max 14 (yudi)` 的 `OS3.0.6.0.VMHCNXM / Android 15`；侧边条依据平板管家 `13.6.2-260922.0.1.pad`。最低 Android 14（API 34）仅是安装要求，不代表其他 ROM 具备相同宿主接口。软件轻捏、双击和滑动动作的效果还取决于前台应用的实现。

## 阅读导航

- [进程与安装](#进程作用域与安装顺序)
- [公共 Hook 与生命周期](#公共-hook-语义与宿主生命周期)
- [配置、消息和存储](#配置进程消息与存储边界)
- [物理按键与动作](#物理按键输入注入与动作链路)
- [手势与窗口](#手势-hook-与按应用防误触)

文件路径除特别说明外，相对 `app/src/main/java/cc/star0/xposed/hyperstylus/`。测试位于 `app/src/test/java/cc/star0/xposed/hyperstylus/`，镜像生产包。

## 进程作用域与安装顺序

Xposed Java 入口在 `java_init.list` 中为 `cc.star0.xposed.hyperstylus.module.FocusStylusModule`。`module.prop` 声明 `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`autoHotReload=true`；当前 `scope.list` 包含 `system`、SystemUI、小米蓝牙、SecurityCore、桌面和平板管家六项。

当前六项声明用于限制可管理的宿主，实际授权子集按 `ScopePolicy.required()` 动态计算。平板管家是侧边条的明确宿主。作用域变更后，`system` 必须重启手机；其他作用域必须重启对应应用进程。配置启停与作用域授权／进程重启分别处理，不把框架集合同步成功写成已经重启生效。

`onModuleLoaded()` 先调用 `ModuleRuntime.loaded()`，记录真实进程名／是否 system_server，并通过 `HostProcessPolicy` 检查宿主；仅目标进程绑定配置桥及公共缓存清理，辅助进程直接返回。system_server 的安装由 `onSystemServerStarting()` 触发，其他宿主由 `onPackageReady()` 触发。每代以 feature ID 去重；安装函数抛异常时单独记日志，不阻止其他功能，已添加的 ID 在本代不会自动撤销重试。个别功能自己的启动重试另由该功能管理。

| 触发条件 | 顺序／feature ID | 实际安装功能 |
| --- | --- | --- |
| system_server 启动 | `mapped-key-input` → `focus` → `charging` → `keys` → `hover` → `system-bars` | 原生注入队列、DisplayContent 焦点、充电 HAL、三键、悬停与系统栏 |
| 包及进程均为 `com.android.systemui` | `focus-notification-gestures` → `window-gestures` → `back-gestures` → `window-config` → `status-icons` | 超级岛插件、窗口标题栏／角落／分屏操作条、返回、焦点／配置客户端和状态图标 |
| 包及进程均为 `com.miui.home` | `home-gestures` → `home-config` | 桌面返回／主页／最近任务，以及焦点／配置客户端 |
| 包及进程均为 `com.miui.securitycore` | `pro-settings` | 笔代数查询适配与配置订阅；不在 `.remote` 安装 |
| 包及进程均为 `com.xiaomi.bluetooth` | `bluetooth` | 原生广播／官方服务观察、遥测快照与 RSSI；不在 `:ui`／`:hello` 等辅助进程安装 |
| 包为 `com.miui.securitycenter`，进程为 `com.miui.securitycenter:ui` | `sidebar-gestures`，随后注册 `sidebar-config` 生命周期客户端 | 侧边条实际窗口消费者、原生可选重定向、配置与每显示器焦点订阅 |

`HostProcessPolicy` 统一限定五个应用包／进程配对；system_server 必须使用框架的 `isSystemServer` 标志，不能只凭进程名准入。`packageReady()` 在匹配前不记录加载器、不构造功能对象；`resume()` 使用同样规则。辅助进程热重载仍先执行旧代资源退出与 handle unhook，再丢弃旧的中性宿主状态，仅保存进程身份，避免恢复不需要的 reader／receiver。包级 `scope.list` 保留六项，不能把 `:ui` 当成包作用域。

框架的 `getRunningTargets()` 反映模块加载记录，可能包含宿主包的辅助进程；早退不代表从框架卸载模块或让列表固定为六条。状态页保留真实条目、PID 与热重载 handle，标记非功能目标并说明加载记录与功能 hook 的区别。

SystemUI、桌面、SecurityCore、蓝牙和平板管家的 Context 初始化交给 `HostLifecycle`；system_server 从实际系统服务获取 Context。平板管家作用域名是包名，实际输入宿主是该 APK 声明的 `:ui` 进程，不能只检查包名便向其主进程安装侧栏逻辑。不能把设置应用的生命周期当成注入进程的生命周期。

## 公共 Hook 语义与宿主生命周期

### `FocusStylusModule.managedHook()`：统一代际门控

这不是 ROM 功能 hook，而是所有功能拦截的统一包装。它透传 HookBuilder 的 priority、exception mode 和 ID；进入拦截前调用 `session.enter()`。旧代已停止接收工作时直接 `chain.proceed()`；获得准入后执行功能 Hooker，并在 finally 调用 `session.leave()`。

`after(executable, callback)` 先执行原方法，成功后执行模块 callback；callback 异常记录日志并保留原返回值，原方法本身抛异常则不会运行 after callback。`afterMethods(type, name, callback)` 遍历该类所有同名、非 abstract 的声明方法，逐个安装；它本身不筛参数、返回类型或 static，调用方必须检查参数并限制行为。本文中“全部声明同名方法”指这个规则，不表示对继承方法或未来所有重载作无条件支持承诺。

`managedHook()` 只负责在途工作门控，不在 `ModuleSession.close()` 中逐个 unhook。新代 `onHotReloaded()` 明确对框架提供的旧 HookHandle 调用 `unhook()`。实际挂接数会随 ROM 声明的构造器／重载、插件和运行时派生类变化，因此下文按 hook 入口及匹配规则记录。

### `HostLifecycle`：唯一公共 `Application.attach(Context)` 后置 hook

文件：`module/HostLifecycle.java`。

- 第一个客户端注册时安装 `android.app.Application.attach(Context)` after hook。原 attach 完成后取得 Application，保存为 `host.application`，再按注册顺序启动客户端。
- 若注入时 Application 已完成 attach，通过 `ActivityThread.currentApplication()` 主动发现；热重载优先使用保存的 Application。
- 按客户端名称去重，每个客户端用 AtomicBoolean 保证仅启动一次。启动失败重置该客户端标记，后续宿主发现可重试。
- 应用宿主启动配置、图标或蓝牙时复用此观察器，避免每个功能另建 attach hook。它不接管 Activity，也不改变 attach 原结果。

### `ModuleSession` 与 API 102 热重载

每个模块实例拥有独立 Session。`enter()/leave()` 统计拦截及其他受保护工作；`onClose()`、`manage()` 和 `register()` 记录资源，短生命周期资源成功结束后可用 `Registration.forget()` 移除退出回调。

退出与恢复顺序必须保持：

1. `onHotReloading()` 调用 `session.close(2000)`：先停止新模块工作，再等待已有回调结束。
2. 在途工作超时／等待被中断时恢复 active，返回失败，此时没有销毁活动资源。
3. 等待成功后先复制并清空资源登记、标记 retired，再按注册逆序尝试全部清理并汇总失败。清理失败会永久保留失败结果，后续请求不能把已部分清理的代际误报为成功。
4. 全部资源退出成功才把 `session.snapshot()` 交给框架，并返回 true。线程、接收器或持有输入无法释放时返回 false，日志提示重启目标进程。
5. `onHotReloaded()` 卸载旧 handles，要求保存状态是 Map，restore 中性状态，再调用 `runtime.loaded()` 与 `runtime.resume()`。
6. `resume()` 使用保存的 system ClassLoader／包 ClassLoader 显式重装各功能，并恢复 SystemBrushSettings。框架不会重放原启动和包加载回调。

可跨代保存的是原始值、字符串、框架 Bundle、集合内的中性元素及真正的宿主对象／宿主 ClassLoader。`validate()` 递归检查容器，拒绝旧模块对象、模块 Class 或模块 ClassLoader；不要保存模块自定义 record/model、Runnable、监听器或 Hooker。状态数据必须在新代重新解码为新模型。

`ModuleSession.onLooper()` 在资源所属 Looper 上清理；已在目标线程时直接执行，跨线程最多等 2 秒。`runAll()` 尝试全部释放并把其余异常附加为 suppressed。只能关闭模块创建的线程，保留系统主 Looper、原生 InputFilter、BLE 服务和 GATT。

反射缓存及 SystemUI holder 布局缓存最先登记退出回调，因此逆序退出时在各功能清理之后清除。任何延迟 callback、外部监听回调或 worker 必须沿用已有 closed/session 检查，避免旧代恢复工作。

### `ReceiverRegistration`：准确注销焦点客户端接收器

文件：`module/ReceiverRegistration.java`。它是资源适配器，不新增方法 hook。

注册时用 `source.createAttributionContext(null)` 创建隔离 Context，记录实际 LoadedApk 和 receiver。退出优先使用原 Context；Context 已消失或遇到“未注册／已注销”异常时，扫描原 LoadedApk 的完整 `mReceivers` 注册表寻找真实 owner，再调用 `forgetReceiverDispatcher()` 和 ActivityManager `unregisterReceiver()`。

只有注册表确认已移除才能接受重复清理；不要仅 catch `IllegalArgumentException` 就认定成功。其他错误继续上抛，使热重载失败可见。

## 配置、进程消息与存储边界

### 不可变配置与默认值

`EnhanceConfig` 只接收 Schema 中的已知键及其精确持久化类型，规范化后发布不可变快照。`ProfileData` 对 JSON、包名、有限整数和范围统一校验；版本不符合当前格式的 profile 会被拒绝。

| 配置 | 默认／合法范围 |
| --- | --- |
| `battery_enabled` | true |
| `signal_enabled` | true；有效显示与轮询条件还需 battery_enabled |
| `focus_key_enabled`／`upper_key_enabled`／`lower_key_enabled` | 全部 true；各自否决该按钮的全局及分应用接管，保留编辑内容 |
| `charge_limit_enabled` | false |
| `charging_notification_enabled`／`attached_notification_enabled` | 全部 false；充电与吸附未充电通知各自独立，均不依赖电量显示或充电限制 |
| `charge_start`／`charge_stop` | 50／80；开始 0..95、停止 5..100、至少相差 5，任一不合法时两者一起回到默认 |
| `signal_one`／`signal_two` | -80／-60；one ≥ -120、two ≤ -20、one < two，不合法时成对回到默认 |
| `display_mode` | 2，稳定值 `0=状态栏`、`1=控制中心`、`2=同时` |
| `pro_settings` | true |
| 八项 `ignore_stylus_*` | 全部 false，包含独立的 `ignore_stylus_split_divider` 和 `ignore_stylus_sidebar` |
| 六项普通按键动作 | 焦点键 `pinch/double_tap`、上键 `slide_up/slide_up`、下键 `slide_down/slide_down` |
| 三项完整映射 | `none`，即未启用完整映射 |
| 原生轻捏／双击 | 默认不接管；PINCH 默认 action=5，范围 0..5；DOUBLE_TAP 默认 action=1，范围 0..4 |
| `revision` | 非负 long，初始 0 |

普通按键分应用规则只有 `AppKeyConfig.enabled` 才优先于全局；新 profile 从当前全局动作复制。防误触按应用是完整替换掩码；原生轻捏／双击按各自 selected 集合选取对应应用动作。这三套规则分别维护，不要混用其启用标记。

### 持久化和同步流程

1. `EnhancePreferences` 在 device-protected Context 中使用私有 `SharedPreferences("hyperstylus")`。保存 revision 为 `max(wallClockMillis, previous+1)`；溢出时拒绝保存。
2. 规范化配置并同步 `commit()` 成功后，才 `notifyChange()` 和向 `android`、SecurityCore、SystemUI、蓝牙、桌面、平板管家显式发送 `CONFIG_CHANGED`。通知失败记日志，但已完成的持久化不应被报告为保存失败。
3. Provider 为 `content://cc.star0.xposed.hyperstylus.config/settings`；只允许 `call("get_all")` 读取配置，使用实际 `Binder.getCallingUid()` 允许本应用 UID、system UID 及五个作用域应用宿主 UID。CRUD 不提供可写路径，extras 中的 UID／包名不作为凭据；平板管家的进程字符串也不是身份凭据。
4. 每个注入进程的 `ConfigBridge` 用一个后台 reader 串行读取 Provider，在 MAIN 上发布。先交付当前快照，再注册广播／ContentObserver 并刷新。`reading/dirty/generation` 合并并发读取和变化。
5. 配置更新广播必须同时匹配 action 与模块包真实发送者 UID；boot 广播只接受 system UID。已有 ready 快照只接受严格更高 revision，拒绝回退和重复版本。
6. Provider 失败每 3 秒最多追加 5 次重试；仍不可用则等 boot 或应用更新。设置页打开／恢复会重发当前持久配置，因此不能只依赖启动轮询。
7. 热重载关 reader、取消任务、注销订阅、清空 listener，等待线程终止，保存 `config.snapshot`、`config.ready`、`config.context`；新代绑定后主动恢复读取。

`Protocol.send()` 为广播设置明确目标包、`FLAG_RECEIVER_FOREGROUND` 并开启共享发送者身份；`Protocol.from()` 将真实 UID 与该安装包 UID 比较。公开的消息 action 名或 `reply_to` 字符串不足以证明来源。各遥测与焦点入口还需执行下文具体来源限制。

### 数据文件

| 位置 | 类型与约束 |
| --- | --- |
| 应用 device-protected 私有 `hyperstylus` | 当前配置 |
| `/data/system/HyperStylus/charge_limit` | SCH1 magic 与三位充电所有权标记 |
| `/data/system/HyperStylus/brush_settings` | 每用户、每原生手势的 original/written 所有权 journal |
| SystemUI 私有 `HyperStylusBattery` | 最后有效显示电量与原采样时间；不参与充电准入 |

`SystemDataFiles` 使用当前 AtomicFile base／`.bak` 恢复事务；写前验证已提交记录和新编码，写入 flush／fsync，完成后读回核验实际字节。已有所有权文件损坏时不得用默认值覆盖它；缺失记录才使用默认值。`ChargeLimitStore` 仅在内容变化时写入，第一次缺失记录也必须允许建立文件。

### 功能开关与共享作用域

三键启停和信号显示提供四个启停开关，分屏操作条、侧边条另有独立防误触开关；原有功能沿用既有开关。基础设施由上层依赖决定，不为每个 hook 再添加重复开关。修改需求计算时以完整快照求并集：

| 有效需求 | 作用域 |
| --- | --- |
| 任一物理键、充电限制、原生轻捏／双击接管、系统栏过滤 | system |
| 电量／信号显示 | SystemUI + 小米蓝牙 |
| 充电限制遥测 | 小米蓝牙，与电量共享 |
| 任一充电通知 | system + 小米蓝牙，与充电限制共享，不额外申请 SystemUI |
| Pro 设置入口 | SecurityCore |
| 返回过滤 | SystemUI + 桌面 |
| 桌面主页／最近任务过滤 | 桌面 |
| 标题栏、角落、超级岛、分屏操作条过滤 | SystemUI |
| 侧边条过滤 | 平板管家 `com.miui.securitycenter` |
| 有效分应用防误触覆盖（包括 0 profile 关闭全局过滤） | 额外 system 焦点 |

潜在手势位计算全局与已选 profile 并集，但实际运行时 profile 仍完整替换全局。分应用防误触开启、至少选中一个应用且并集中至少一项过滤开启时，额外需要 system 焦点；全局或其他已选应用仍有过滤时，0 profile 也需要焦点以关闭该应用中的全局过滤。未选 profile 不贡献需求。按键映射和原生轻捏／双击接管需要 system；`pinch` 动作及原生轻捏动作 5 不额外申请 SystemUI。默认实际需求为 system、SystemUI、蓝牙、SecurityCore；所有功能关闭后为空。

快捷环沿用 ROM 原生能力：system_server 写入每用户轻捏设置，并由原生服务通知 SystemUI 显示面板。原生 SystemUI 是显示宿主，不等于模块必须注入该进程；模块只为电量显示和对应防误触功能申请 SystemUI，不覆盖原生面板支持资源，也不为快捷环启动查询同步读取 Provider。

应用保存并广播配置后，`ModuleServices.configure()` 驱动每个具体框架连接的 `ScopeSynchronizer`。所有读取／提交在 IO，状态和回调串行回主 scope；至多一个待审批请求。使用 API 102 的 `requestScope(List<String>, OnScopeEventListener)` 与 `removeScope(List<String>)`，操作后读取实际 `getScope()`。只移除六个明确管理的宿主，保留未知额外项。

部分批准／拒绝后，同一连接不因普通刷新或无关设置重新弹窗；某作用域的最后需求关闭后再开启，或明确重试，可以再申请。迟到批准按最新需求撤销；旧连接关闭后不接受旧回调，多框架分别计算。移除失败保留诊断，但不阻塞其他缺失宿主的申请。

若 system_server 已加载本模块，最后的 system 需求消失时，通过可信 `PREPARE_SCOPE_REMOVAL`／`SCOPE_REMOVAL_READY` 请求先恢复原生手势 journal 并确认按键／激光释放，再移除。请求只能来自模块 UID，回执只能来自 system UID，并匹配随机 request ID、当前配置 revision；失败／超时保留 system，提示重启手机加载新版后重试。尚未注入的迟到授权没有当前状态需等待，可以直接撤销。普通热重载仍保留 journal 给新代。

作用域移除不卸载当前进程，已有 hooks 必须继续正确读取开关。

## 物理按键、输入注入与动作链路

本节的 hook 入口在 `ModuleRuntime.systemServer()` 安装；动作执行和事件识别辅助类本身不新增 hook。本节的文件均位于 `app/src/main/java/cc/star0/xposed/hyperstylus/`。物理按键拦截、事件识别、状态机与动作执行是不同层，修改时应保持这些边界。

### StylusKeyHook：拥有一整次物理按压

文件：`runtime/keys/StylusKeyHook.java`。目标类是 `com.miui.server.input.stylus.MiuiStylusShortcutManager`。

| 注册点 | 调用时机 | 功能 |
| --- | --- | --- |
| 目标类的全部声明构造器 | 原构造器成功返回后 | `start(receiver)` 捕获管理器与 Context，初始化配置、动作执行器和设备监听。 |
| 全部名为 `onSystemBooted` 的声明方法 | 原方法成功返回后 | 再次尝试初始化，补上构造阶段 Context 尚未准备好时的启动。 |
| `shouldInterceptKey(KeyEvent)` | 替代/放行拦截链 | 为已配置按钮拥有完整 DOWN 到 UP，返回 `true` 吞掉物理事件，防止 ROM 同时运行自己的短按/长按动作。 |
| `getDelayTime(KeyEvent)` | 替代/放行拦截链 | 对模块拥有的物理按压返回 `-1L`；软件手势返回 `0L`；完整映射为虚拟键盘，执行原生策略。 |

安装时类不存在只记录“原生笔快捷服务不可用”，各注册步骤独立捕获错误。构造器和启动回调不是动作触发点。`start()` 记录 `input.shortcutManager`，调用 `ForegroundApp.registerShortcutManager()`，优先读取管理器 `mContext`，其次读取 ActivityThread 系统 Context；使用 AtomicBoolean 阻止重入，实际初始化移到主线程，Context 或服务未就绪时可在下一次回调重试。动作执行器尚未建立时，按键拦截也尝试 `start()`，仍未就绪则 `proceed()`。

设备身份由 `StylusDevices.version(InputDevice)` 读取该固件的 `InputDevice.isXiaomiStylus(): int`；不存在接口、结果不大于零或反射失败时视为非小米笔，不按设备名称猜测。`buttonFor()` 定义三个逻辑按钮：

- 焦点键为 keyCode `193`，或者笔版本恰为 `3` 时的 `194`，逻辑索引 `0`。
- `KEYCODE_PAGE_UP` 为上键，逻辑索引 `1`；`KEYCODE_PAGE_DOWN` 为下键，逻辑索引 `2`。
- 负 deviceId、非笔设备以及带模块合成 scanCode 的事件均不进入物理按键处理。

`shouldInterceptKey()` 的判断顺序决定行为，不能任意调整：

1. 匹配热重载已取消集合 `(deviceId, keyCode, downTime)` 的事件返回 `true`，吞掉旧按压的后续事件。
2. `ProGestureAdapter.mappedAppGesture()` 识别的 `195..197` 软件手势，以及 `194` 软件轻捏，返回 `false`；要求 scanCode `0x5354`、笔来源、DOWN/UP，避免再被当作物理笔键。
3. 非三个物理按钮执行 `chain.proceed()`。
4. 每次新的初始 DOWN（repeatCount 为 `0`）按 `(deviceId, keyCode)` 建立不可变 Binding；同一 downTime 的重复 DOWN 直接吞掉。新 DOWN 替换旧 Binding 时，先为旧状态排入 `FLAG_CANCELED` 的 UP。
5. 此刻读取 `ConfigBridge.current()` 和 `ForegroundApp.packageName(manager)`，一次性固定单击/长按动作对、完整映射目标键、原始 DOWN 和启用 epoch。已启用分应用规则优先，未启用/无规则/前台包名缺失时使用全局配置；对应按钮关闭时 actionsFor 返回 default/default、fullKeyCodeFor 返回 0，原存储动作保持。
6. 完整映射未启用且单击与长按都为 `default` 时，直接放行整条原生路径。否则拥有该按压；匹配同一 downTime 的后续事件送主线程执行并返回 `true`。缺少 Binding 或 downTime 不匹配的事件放行。

注意：配置缺省动作是焦点键 `pinch/double_tap`、上键 `slide_up/slide_up`、下键 `slide_down/slide_down`；`default` 是用户可选的原生语义，不等于配置文件缺失时的默认动作。完整映射目标优先于单击/长按动作。按住期间切应用或修改设置不会改变释放目标。Binding 记录不会在每次 UP 中立即删除；真正的活动按压分别由两个 dispatcher 管理，新 DOWN 会替换记录。

按钮开关关闭是显式取消：ConfigBridge listener 在 MAIN 递增对应 epoch，发送 canceled UP 并记住物理三元组，吞余下物理流。排队中的旧 DOWN 还检查 epoch，快速重新开启和关闭遍历后才发布的旧 Binding 不能复活。映射释放失败保留诊断和目标状态，不确认 system 可移除；激光还需等待原生 Looper 完成释放。重新开启沿用存储动作，两个 dispatcher 不因切开关被永久 close。

`getDelayTime()` 先判断已取消为 `-1L`，再判断软件手势为 `0L`，最后对匹配模块 Binding 的物理事件返回 `-1L`，其他 `proceed()`。软件手势“放行”是返回拦截结果 `false`，不调用原管理器；普通事件“放行”才是执行原方法。

设备添加/属性变化时失效悬停位置；设备移除时还会删除取消记录、取消普通按压、移除该设备的完整映射 owner 并释放已无 owner 的目标、清除该设备 Binding。退出注册键 `input.keys` 在主线程清理：先将 Binding 的三元组保存为 Bundle `input.cancelledPresses`，再关闭两个 dispatcher、移除主线程任务和输入设备监听、释放 ForegroundApp 管理器引用；聚合失败并上抛给会话退出，不能把失败视为清理成功。

### KeyPressDispatcher / PressGesture：380ms 单击与长按

文件：`runtime/keys/KeyPressDispatcher.java`、`runtime/keys/PressGesture.java`。这两个类不注册新的 Xposed hook，而是执行前述按键 hook 的状态机。

- 每个 `(设备, 物理键码)` 只有一个 `PressGesture`，状态为 `PRESSED → LONG → RELEASED` 或 `PRESSED → RELEASED`。
- 初始 DOWN 创建状态、取消同键旧状态，并基于原 downTime 启动 `380ms` 计时；计时投递有延迟时扣除已过去时长。
- 计时器只触发一次长按。匹配 UP 若仍为 PRESSED，按事件时间计算实际时长：达到 380ms 就执行长按，否则执行单击。这保证主线程忙时不会把长按误判为单击。
- canceled UP 只释放，不触发动作；不匹配 downTime 的 UP 无效。UP 在 finally 中进入 RELEASED 并调用 release，动作抛异常也必须配平释放。
- 焦点键的某一动作是 `default`，而该按压整体被自定义模式接管时：单击用 ORIGIN 调原生 DOWN/UP；长按通过 `nativeFocusLong()` 启动原生激光，存储 release 回调，直到物理松开/取消才释放。两个动作均 `default` 时不会经过此 dispatcher。
- 上/下键单击/长按的 `default` 由 `StylusActions.dispatch()` 各自转换为原生语义，不通过焦点激光路径。
- `forget(device)` 和 `close()` 取消全部相关活动状态；定时器和动作执行都会检查当前 session 是否 active。关闭失败会保留失败原因并拒绝静默退出。

相关验证：`PressGestureTest` 覆盖延迟计时、错配 UP、取消和动作异常时释放。

### FullKeyDispatcher：即时 DOWN、保持重复与最终 UP

文件：`runtime/keys/FullKeyDispatcher.java`。无新 hook，目标事件通过 `MappedKeyInjector.deliverFull()` 发送。

- Physical 身份仍为 `(deviceId, physicalKeyCode)`；目标使用标准虚拟键盘 `deviceId=-1`，Target 按 mappedKeyCode 合并。多个笔键／笔设备映射到同一目标时共享 Held 组、一次目标 DOWN 和一个定时器，最后一个 owner 松开才发送目标 UP。设备移除只移除该设备的 owner，其他设备仍持有目标时继续按住。
- 记录 latestDown 防止旧 DOWN、重复初始 DOWN 或结束后的重放复活旧按压；替换按压先取消释放旧目标，错配/孤立 UP 不释放新目标。
- 第一次物理 DOWN 立即创建键盘目标 DOWN，保留 downTime、eventTime、metaState 和 flags；deviceId 改为 `KeyCharacterMap.VIRTUAL_KEYBOARD`，来源为严格 `SOURCE_KEYBOARD`。`KeyboardMapping` 根据已核对的 Generic.kl 提供键盘扫描码（如数字 5 为 6、数字小键盘 5 为 76）；没有键盘位置的 Android 键码使用 0，不伪造位置。不能复用笔 deviceId：InputDispatcher 丢弃原物理键时按 deviceId 取消非指针按键，会向同设备映射键补 canceled UP。
- 生产路径使用 `ViewConfiguration.getKeyRepeatTimeout()` 和 `getKeyRepeatDelay()`，首个重复与物理 downTime 对齐；模块定时器递增 repeatCount，第一个重复添加 `FLAG_LONG_PRESS`，后续移除该标志。生产路径忽略来自物理按压的重复 DOWN，避免双重重复。无 Scheduler 的构造方式主要供状态机测试使用，可按物理 repeatCount 转发。
- 每个重复都检查 dispatcher 未关闭、Held 仍在注册且仍有 owner。重复注入失败时清空该组 owner、取消计时、尝试 canceled UP、报告错误；其他目标继续运行。
- 普通松开使用与 Held 初始 DOWN 相同 downTime 的目标 UP；设备断开、按压替换、退出等产生带 `FLAG_CANCELED` 的 UP。
- DOWN 注入失败也尝试 canceled UP。UP 注入失败时 Held 保留为空 owner 组，后续 begin/forget/close 重试释放；不能覆盖尚未释放的失败目标。一次失败不会阻止其他组释放，secondary failure 通过 suppressed exception 汇总。

相关验证：`FullKeyDispatcherTest` 和 `CompleteKeyInjectionTest` 覆盖即时按下、长按重复、两键同目标、跨设备 owner 隔离、乱序与替换、失败释放重试、定时器取消及元数据保留。

### MappedKeyInjector：捕获 system_server 原生输入队列

文件：`runtime/keys/MappedKeyInjector.java`。hook 类 `com.android.server.input.InputManagerService` 的所有声明 `start`、`systemRunning` 方法，均在原方法成功返回后捕获 `mNative`。

动态遍历 mNative 对象的类继承链，仅接受非 static、非 abstract 且返回 int 的确定 ABI：

```java
int injectInputEvent(InputEvent event, boolean, int, int, int, int)
```

调用参数固定为 `(event, false, -1, 0, 0, flags)`。返回 `0` 才算注入成功。后置 hook 保存 `input.nativeService`；热重载从中恢复动态方法，在 `input.nativeInjector` 清理时只释放模块持有的 active 目标，不销毁宿主 mNative。

若常规启动捕获已错过，晚加载路径通过 ServiceManager `input` Binder 读取本地 `IInputManager.Stub.asInterface()`，要求对象类名恰为 `com.android.server.input.InputManagerService` 后读取 mNative；远程代理不符合保留设备身份的注入要求。

所有注入只允许 SYSTEM_UID。软件 Pro 手势的 flags 为 `FILTERED(0x04000000) | DISABLE_REPEAT(0x08000000) | PASS_TO_USER(0x40000000)`，保留真实笔设备。完整映射仅传 `DISABLE_REPEAT`，让原生 beforeQueueing 决定系统键消费／向应用交付，并使用标准虚拟键盘身份；完整映射由模块生成重复，禁止原生再次重复。

- `deliver(pair)` 只接受同设备、同键码、同 downTime 的一对笔来源 DOWN/UP，code `194..197`、scanCode `0x5354`、repeatCount 为零。发送时复制事件并添加 `KeyEvent.FLAG_FALLBACK`，将模块生成的 Pro 动作标记为原物理键的最终映射，阻止应用未消费时按 BUTTON_7..10 再回退成 DPAD_CENTER；保留原始笔身份、键码、时间和来源，不修改调用方事件。即使 DOWN 被拒绝或抛异常仍尝试 UP，聚合失败，不改走另一注入通路。
- `deliverFull(event)` 只接受虚拟键盘 `deviceId=-1`、合法目标键、与 `KeyboardMapping` 一致的 scanCode、严格键盘来源及 DOWN/UP；UP repeatCount 必须为零。真实物理身份留在 Binding／Physical／owner 中，负责配置快照、断连取消和最终释放。

相关验证：`MappedKeyInjectorTest`、`CompleteKeyInjectionTest` 核对 UID、ABI、拒绝/异常的配平释放、设备身份与合成标记；`MappedProFallbackTest` 覆盖未消费的 Pro 动作不附加 DPAD_CENTER、普通完整键盘映射保留原生回退。

### StylusHover：独立悬停观测，不消费原输入

文件：`runtime/keys/StylusHover.java`。目标类 `com.miui.server.input.stylus.MiuiStylusTouchFilmManager`，在全部声明构造器以及所有声明 `onStylusConnectionStateChanged` 方法成功返回后向主线程排入 `ensureMonitoring()`。

从管理器读取 `mMiuiGestureMonitor`，注册独立代理 `com.miui.server.input.gesture.MiuiGestureListener.onPointerEvent(MotionEvent)`，要求接口及 callback/registration 为确定的 void ABI。同一 monitor 使用 WeakHashMap 防重复注册；这是注册 listener，不是额外替换 ROM onPointerEvent。

- 只采样笔尖/橡皮擦的 DOWN、POINTER_DOWN、MOVE、HOVER_ENTER、HOVER_MOVE，ACTION_CANCEL 清空缓存。
- 仅默认 display；坐标必须有限、非负且在当前物理宽高内。通过 InputManager 枚举笔设备，跳过 descriptor 为空的设备，只在恰好一个可用小米笔身份时采样；多笔身份有歧义时不借用位置。
- Position 保存笔 HID 身份、指针设备、屏幕宽高/旋转、坐标和原采样时间；有效期为 `5000ms`，未来时间也无效。
- `pointFor(film, device)` 在已有缓存且请求 deviceId 非负时，要求缓存仍新鲜、请求笔 deviceId 与当前唯一笔／descriptor 一致、显示框架未变化；不满足则丢缓存并尝试以下 ROM 字段路径。负 deviceId 不使用独立采样缓存。
- 无可信缓存时，仅当 ROM `mIsTouchFilmStylusConnected == true` 且存在 `mUpdatePointLock`，才在该锁下复制合法 `mStylusHoverPoint`。否则返回固件默认哨兵 `PointF(-1, -1)`，不写回管理器，不沿用断连后留在字段里的旧坐标。
- 观察回调参与 session enter/leave；异常只被忽略，不影响系统原生事件。输入设备变化会失效位置。
- `input.hover` 退出在主线程保存 `input.hoverOwners`、`input.hoverContext` 和 Bundle `input.hoverPosition`，移除任务、逐一注销 listener、清空弱引用与位置。新代显式恢复 owner 并重新注册，只恢复仍在 5 秒内的坐标。

相关验证：`StylusHoverPointTest` 覆盖复制、断连残留点、缺少字段/非法坐标及哨兵返回。

### ProGestureAdapter：模块 Pro 事件识别

文件：`runtime/keys/ProGestureAdapter.java`。此类不注册 Xposed hook。

`mappedAppGesture()` 仅识别 scanCode 为 `0x5354`、具有笔来源、keyCode 为 `195..197` 且 action 为 DOWN／UP 的模块软件手势，供物理按键 hook 放行。`194` 软件轻捏另由 `StylusKeyHook` 识别。`Dispatch<T>` 为原生激光按压和释放提供可抛异常的回调类型；真实 Pro 手势仍由 ROM 路由。

### StylusActions / NativeStylusDispatch：一次动作到一个端点

文件：`runtime/keys/StylusActions.java`、`NativeStylusDispatch.java`、`MappedProGesture.java`、`StylusKeyInjector.java`。这些是动作执行/适配层，不额外注册 hook。

`StylusActions.dispatch()` 先解析 `keyboard:<合法 KeyEvent code>`，再查预定义键盘动作（方向键、PAGE_UP/DOWN、back、media_play_pause、power），最后进入软件命令分支。保持可空的 Integer lookup，避免混合 int/Integer 三元表达式把 null 自动拆箱，导致非键盘命令 NPE 且 R8 删除执行分支。

| 动作 | 实际链路 |
| --- | --- |
| `none` | 已消费物理按压后不执行命令。 |
| `pinch` / `double_tap` | `NativeStylusDispatch.gesture(194/195, device)`。 |
| `slide_up` / `slide_down` | ROM 支持 remote control 时调用 `previous/next`；支持当前应用 slide page 时普通键盘 PAGE_UP/DOWN；其他发送映射 Pro `196/197`。 |
| `home` | ACTION_MAIN + CATEGORY_HOME + NEW_TASK。 |
| `recents` | LocalServices → StatusBarManagerInternal → `toggleRecentApps()`。 |
| `partial_screenshot` | ShortCutActionsUtils `triggerFunction("stylus_partial_screenshot", "long_press_page_up_key", null, false)`。 |
| `full_screenshot` | `triggerFunction("screen_shot", "stylus", null, false)`。 |
| `quick_note` | `triggerFunction("note", "stylus", Bundle(scene="app"), false)`。 |
| `volume_up/down` | AudioManager adjustVolume，显示原生音量 UI。 |
| `flashlight` / `voice_assistant` | `triggerFunction("turn_on_torch"/"launch_voice_assistant", "stylus", null, false)`。 |
| `screen_off` | PowerManager `goToSleep(uptimeMillis)`。 |

上键 `default` 单击/长按分别转换为 `page_up/partial_screenshot`；下键分别为 `page_down/quick_note`。焦点 default 的原生激光处理由 KeyPressDispatcher 先分流。

`MappedProGesture.execute()` 对 `194..197` 只选择一个真实端点：

- `194` 且 TouchFilm `mCurrentPinchFunction == 5`、`mCurrentVirtualLaserStatus == 0` 时读取可信悬停点，调用 `updateShortcutPanelDefaultPosition(point)`，再调用 `mMiuiInputManagerInternal.showStylusPinchShortcutPanel(point)` 一次。void 回调即是实际动作，不因 ROM 队列 false 返回再注入一遍 194。
- `195` 先调用 ShortcutManager `onVirtualLaserDoubleClickEvent(KeyEvent)`，参数为保留笔身份的合成 DOWN；整数返回 `0` 表示原生虚拟激光已消费，此时不继续投递应用事件。
- 剩余 `194..197` 调用 `StylusKeyInjector.deliverMappedPro()`，发原始笔 deviceId + `SOURCE_STYLUS | SOURCE_KEYBOARD` + `0x5354` 的键对，经 MappedKeyInjector 保留原设备身份。该路径要求屏幕处于 interactive，否则记录失败。前台应用必须实现相应命令才会有软件效果。

普通 `keyboard:*` 和键盘动作走 InputManager `injectInputEvent(InputEvent, int)`，deviceId `-1`、SOURCE_KEYBOARD、scanCode `0x5354`，每个 DOWN 无论接受/拒绝/异常都尝试 UP。该单次键对与完整映射的持续按压用途不同；完整映射也使用虚拟键盘身份，但沿用原生 mNative 注入并显式禁止额外原生重复。软件 Pro 手势必须继续保留真实笔设备，不能替换成虚拟键盘。

相关验证：`StylusActionDispatchTest` 覆盖键盘/非键盘分支和 default 语义；`MappedProGestureTest` 覆盖面板只调用一次、激光先消费及应用手势保留设备。

### NativeLaser：原生焦点长按的跨线程配平

文件：`runtime/keys/NativeLaser.java`。没有新 hook。`NativeStylusDispatch.focus()` 使用 libxposed `Invoker.Type.ORIGIN` 调 `shouldInterceptKey(KeyEvent)`，绕过自己的物理拦截。该方法“调用成功”不等于 ROM 返回消费确认。

长按先读取/通过 `mMiuiInputManagerInternal.obtainLaserPointerController()` 获取 `mLaserPointerController`，核对固件 `com.miui.server.input.stylus.laser.LaserPointerController` 类型、mHandler、mIsKeyDown 及 `onLaserKeyLongPressed()/interceptLaserKeyUp(): void` ABI。NativeLaser 在控制器自己的 Looper 发原生 DOWN；确认 `mIsKeyDown` 为真后，只对已存在的 message `5` 长按计时器移除并即时 dispatch，使长按从模块 380ms 判断后立刻执行，避免再等 ROM 第二轮阈值。

每个 Press 单独注册 `input.nativeLaserPress` 清理资源。cancelled 为跨线程原子标志，active/activating/finishing 只在控制器 Looper 更新；重入释放在 DOWN 完成后配平一次，尚未开始的退出不会发送虚假的 UP。release 回调最终调 `interceptLaserKeyUp()`，配平成功才忘记 Registration。热重载退出使用 `ModuleSession.onLooper()` 移除两个回调、完成释放，若释放异常或原生 edge 仍在运行则上抛，确保不能带着未释放激光状态切换代际。

## 手势 hook 与按应用防误触

源码目录：`app/src/main/java/cc/star0/xposed/hyperstylus/runtime/gesture/`。这组功能只接管系统手势观察器、窗口手势消费者或超级岛的横滑动作，不改写前台应用收到的原始笔迹输入。新增入口必须沿用 `module.managedHook`，保持输入流归属、嵌套调用归属和退出清理。

### 配置与作用域

`EnhanceConfig.gestureMask()` 将八个全局开关编码为掩码；`GestureAppConfig` 的当前 JSON 格式为 version 2。

| 位 | 配置字段 / 存储键 | 功能与消费进程 |
| --- | --- | --- |
| 1 / `BACK` | `ignoreStylusBack` / `ignore_stylus_back` | SystemUI 返回及桌面返回 |
| 2 / `SYSTEM_BARS` | `ignoreStylusSystemBars` / `ignore_stylus_system_bars` | system_server 的系统栏边缘滑动 |
| 4 / `HOME_RECENTS` | `ignoreStylusHomeRecents` / `ignore_stylus_home_recents` | 桌面主页与最近任务手势 |
| 8 / `CAPTION` | `ignoreStylusCaption` / `ignore_stylus_caption` | SystemUI Shell 顶部／小窗底部窗口控制器 |
| 16 / `CORNER` | `ignoreStylusCornerFreeform` / `ignore_stylus_corner_freeform` | SystemUI Shell 全屏角落自由窗口命中 |
| 32 / `FOCUS_NOTIFICATION` | `ignoreStylusFocusNotification` / `ignore_stylus_focus_notification` | SystemUI 超级岛横滑唤出 |
| 64 / `SPLIT_DIVIDER` | `ignoreStylusSplitDivider` / `ignore_stylus_split_divider` | SystemUI 分屏操作条与菜单按钮 |
| 128 / `SIDEBAR` | `ignoreStylusSidebar` / `ignore_stylus_sidebar` | 平板管家 `:ui` 侧边条 |

`ALL = 255`。当前 version 2 的 profile 掩码合法范围为 `0..255`。分应用开关开启且当前包名位于 `selected` 时，应用 profile **完整替换**全局掩码；profile 为 0 也必须生效。未选中包、没有有效焦点或 profile 缺失时回到全局掩码。取消选择会保留已编辑的 profile，重新选择可继续使用。不要把掩码合并改成按位 OR，否则无法给某个应用关闭全局启用的防误触。

设置页八项防误触标题统一使用“禁止”，全局与分应用页面共用同一组资源。每个 `ignore_stylus_*` 配置键对应独立位；标题文本与持久化键分别维护。

`GestureFocusBridge.enabled(event, feature, global)` 用事件的 display ID 选择对应显示器的焦点包名，不根据 SystemUI 或桌面自身包名判断应用。分应用功能关闭、feature 为 0、event 为空、`MotionEvent.getDisplayId()` 不可用或调用失败时返回传入的全局值。`GestureAppConfig.feature()` 将 `back` 与 `home-back` 都映射到 `BACK`，将 `split-divider` 映射到 `SPLIT_DIVIDER`；角落与分屏消费者也可直接使用对应常量。

### `GestureFocusBridge`：窗口焦点采集与跨进程同步

安装位置为 system_server；SystemUI、桌面和平板管家 `:ui` 在应用生命周期启动后调用 `startClient()`。

**hook 目标及顺序：**

- `com.android.server.wm.DisplayContent` 的全部构造器：原构造器返回后调用 `Server.observe(display, false)`。
- 该类声明的具体实例方法 `updateFocusedWindowLocked(...)`：仅选返回类型为 `boolean` 的方法，原方法返回后读取焦点；参数个数不被写死。
- 该类声明的具体实例方法 `removeImmediately()`：必须无参数，原方法返回后从显示器映射移除该 display ID。

**采集与发布逻辑：**

1. 从 `DisplayContent.getDisplayId()` 取得非负 ID，从 `mCurrentFocus.getOwningPackage()` 读取合法包名；没有焦点编码为空字符串，移除显示器直接删除映射。
2. 从 `mWmService.mContext` 获取 system Context。另从 `mWmService.mRoot.getTopFocusedDisplayContent()` 读取用于系统轻捏／双击配置的全局顶层焦点，保存为 `brushApp`。
3. 映射或 `brushApp` 改变时递增 revision，立即更新本进程 `LOCAL` 快照，然后在 MAIN Handler 上合并投递一次 `publish()`。不要在 WMS 焦点回调中直接发跨进程广播或写系统笔设置。
4. `publish()` 调用 `SystemBrushSettings.start()` 与 `focus(brushApp)`，再向 `com.android.systemui`、`com.miui.home` 和 `com.miui.securitycenter` 显式发送 `<模块包名>.GESTURE_FOCUS`，消息带 `revision` 及以显示器 ID 字符串为键的 `displays` Bundle。
5. server 注册 `<模块包名>.GESTURE_FOCUS_REQUEST` 请求接收器；`trustedRequest()` 只接受 system UID，或经过 `Protocol.from()` 校验的实际桌面／平板管家 UID。客户端首次订阅时向 `android` 发送快照请求。允许请求不代表允许发布 UPDATE，后者仍仅接受 system UID。

**客户端与数据验证：**

- 使用 `ReceiverRegistration.attach()` 在 MAIN 上注册导出接收器；实际接受 `UPDATE` 前必须验证 `getSentFromUid() == Process.SYSTEM_UID`。
- `Snapshot.accept()` 一次性校验整个快照：revision 必须非负且严格大于现值，displays 必须存在，每个 ID 必须可解析且非负，包名必须为空或合法。任何条目无效时保留原快照。
- 快照是不可变 Map，通过 volatile State 发布；新快照中的空焦点和缺失显示器不能沿用旧包名。
- `enabledOnDisplay(display, feature, config, global)` 为窗口输入策略提供显式配置快照和显示器查询，与事件入口共用 LOCAL 焦点及 profile 完整替换规则；没有有效焦点或显示器时回退传入的全局值，不新增 Binder／Provider 读取。
- `observeChanges(listener)` 返回可忘记的 Registration。广播接收与客户端热重载恢复共用 `acceptClientSnapshot()`：只有真正 system 发送者且快照整体有效、revision 严格更新时通知；单个 listener 异常记录日志并继续。窗口调用方负责会话门控和所属 Handler，退出时忘记登记。
- `startClient()` 由会话 enter/leave 保护，静态订阅标记避免同代重复注册；线程不同则投递 MAIN 注册工作。

**热重载：** server 保存 `focus.server.snapshot`、`focus.server.context`、`focus.server.brush`，client 保存 `focus.client.snapshot` 及注册 context；退出时在 MAIN 清除待处理任务、注销接收器并保存中性 Bundle，新代重新订阅。客户端真实注销交给 `ReceiverRegistration`，不能用新 Context 随意调用旧接收器的 `unregisterReceiver()`。

主要回归：`GestureFocusSnapshotTest` 验证真实 system sender、严格递增 revision、整包拒绝非法值、空焦点与显示器移除。系统笔设置联动还应参照 runtime/nativegesture 包的测试。

### 公共输入层：`MotionGestureFilter`、`PenMotionStream` 与 `StylusBackGestureHook.Gate`

`MotionGestureFilter` 本身是 hook 安装器，`PenMotionStream` 是与 Android 类型解耦的状态机，`StylusBackGestureHook.Gate` 将 `MotionEvent` 转换为状态机参数。每个 hook 方法拥有自己的 Gate；Gate 的弱引用 Map 按接收对象 owner 隔离，流身份为 `(downTime, deviceId)`。

**入口匹配规则：** `matches(method, name, input)` 只允许名称匹配、非 static、非 abstract、至少一个参数、返回 `void` 或 `boolean` 的方法；第一个参数既必须满足给定 input 的类型约束，也必须能接收 `MotionEvent`。后续参数保留原值。不要把一个仅同名、不同输入类型的方法也安装进去。

**笔识别：** 遍历全部 pointer，出现 `TOOL_TYPE_STYLUS` 或 `TOOL_TYPE_ERASER` 即视为含笔流。`SOURCE_STYLUS` 本身不足以证明当前 pointer 是笔，手指与鼠标仍须通过；不能只检查第 0 个 pointer。

**状态机：**

- `DOWN` 创建流并固定该次手势的配置。中途修改配置对本流无影响。
- 未识别到流时，仅在“当前配置启用且事件含笔”时返回 `DROP`，否则 `PASS`。
- 已有流但 downTime/device 不匹配时清除旧流，不继承旧拦截状态。
- 手指流先被原观察器收到，后来笔加入且 DOWN 时策略启用：返回一次 `CANCEL_AND_DROP`，将 `suppressed` 固定为 true。后续 MOVE、POINTER、UP 都 DROP，不再补 CANCEL。
- 从 DOWN 开始即含笔且策略启用：直接 DROP，原观察器尚未收到输入，不需要 CANCEL。
- `CANCEL` 删除流；原流已 suppressed 时 DROP，否则 PASS。`UP` 决策后删除流。

**hook 执行逻辑：**

1. 第一个参数不是 MotionEvent 时直接 `chain.proceed()`。
2. 可选 `prepare(receiver)` 用于获得宿主 Context；准备失败记录日志，但继续完成正常过滤判断。
3. 取当前全局开关，再由 `GestureFocusBridge` 选择分应用策略，交给 Gate 决策。
4. 用 ThreadLocal `Origin(feature, blocked, tool, source)` 标记同步调用归属。如果外层已标记 blocked，内层保留该外层标记，以便终端 guard 正确识别取消过程。
5. PASS 原样 proceed；DROP 对 boolean 方法返回 `false`，对 void 方法返回 `null`；CANCEL_AND_DROP 复制 MotionEvent、改成 `ACTION_CANCEL`、替换第一参数调用原方法，再 recycle 复制事件并吞掉原事件。
6. finally 必须恢复外层 Origin 或 remove 当前 ThreadLocal，原方法异常也不能污染后续任务。每个入口第一次真正拦截时记一次诊断日志。

`installSelected()` 接受按事件求值的 predicate 和可选 `InputLifecycle`，供窗口控制器沿用同一 Gate／Origin 并在前后记录实际 inputContext、原方法是否成功执行及模块停止权。现有 `install()` 仍使用全局／分应用 predicate 包装该入口；生命周期 callback 不增加 hook，也不能把其他接收对象的上下文归属借给当前事件。

主要回归：`PenMotionStreamTest` 验证笔加入手指流只补一次取消、DOWN 固定策略、owner 隔离及 device/downTime 不串流；`FocusNotificationToolTest` 验证多 pointer 笔尖／橡皮擦识别、手指和鼠标不误判。

### `StylusBackGestureHook`：返回手势观察器

**SystemUI hook：**

- `com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.onMotionEvent(MotionEvent): void` 为上游入口。
- 只有上游至少安装一个方法时，才安装 `com.android.systemui.navigationbar.gestural.BackPanelController.onMotionEvent(MotionEvent): void`，防止仅吞返回反馈而放过实际返回动作。
- SystemUI 两处均要求精确的单 MotionEvent 参数及 void 返回。

**桌面 hook：** `com.miui.home.recents.GesturesBackController.onPointerEvent(...)`；使用通用入口匹配规则，第一参数能接收 MotionEvent，返回 void/boolean，其余参数保留。由 `StylusSystemGestureHook.installHome()` 统一安装。

三个入口分别以 `back`／`home-back` 标签交给 `MotionGestureFilter`，共同使用 `ignoreStylusBack` 和 `BACK` 位。只吞原生返回观察器的笔流；框架提供给前台应用的输入流继续正常工作。不存在上游类或签名时记录 observer 计数及不可用日志，不能靠安装任意同名方法猜测支持。

### `StylusSystemGestureHook`：系统栏、桌面主页／最近任务及终端防护

#### 系统栏

安装于 system_server。

- after-hook `com.android.server.wm.SystemGesturesPointerEventListener` 的全部构造器：优先从参数中取 Context，参数没有则读接收对象 `mContext`，只成功绑定一次 ConfigBridge。
- hook 该类 `onPointerEvent(...)` 的符合通用规则的方法，使用 `system-bars`、`ignoreStylusSystemBars` 和 `SYSTEM_BARS` 位；处理事件前也尝试抓取 Context，支持首次安装时错过构造器的情况。
- hook `com.android.server.wm.DisplayPolicy.requestTransientBars(...)` 和 `com.android.server.wm.InsetsPolicy.showTransient(...)` 的具体实例 void 方法。这两类是系统栏最终显示入口，仅当当前 ThreadLocal Origin 存在、blocked 为 true 且 feature 为 `system-bars` 时返回 null。

终端 guard 不会根据全局开关直接屏蔽一切 transient bars；键盘、鼠标、手指、无归属任务以及其他同步功能仍原样 proceed。全局 `ignoreStylusSystemBars` 开启但终端未被拦截时，可打印归属和宿主调用路径辅助排查；去重后最多保留六条不同路径。

#### 桌面主页与最近任务

安装于 `com.miui.home` 主进程。

- 遍历 `com.miui.home.recents.GestureInputHelper`、`com.miui.home.recents.InterceptingGestureInputHelper` 的继承链，安装声明的 `onInputEvent(...)`，输入约束使用 `InputEvent`，运行时只处理 MotionEvent。
- after-hook 两个 helper 的全部构造器，用真实 `receiver.getClass()` 再扫描运行时继承链，以覆盖派生消费者；扫描在到达 `GestureInputHelper` 后停止。
- 额外安装 `com.miui.home.recents.GestureTouchEventTracker.onTouchEvent(...)` 的通用入口。
- 这些观察器使用 `home-recents`、`ignoreStylusHomeRecents` 和 `HOME_RECENTS` 位。Inputs 使用 Set<Method> 防止重复安装，失败后移除该 Method 的去重标记。
- 对继承链中 `pilferPointers(): void` 的具体无参数实例方法安装 guard；当 Origin blocked 且 feature 以 `home-` 开始时直接返回 null。该条件同时覆盖桌面返回和桌面主页／最近任务，防止被取消的笔流继续抢走前台应用的 pointer 所有权。

这些 guard 与观察器必须使用同一 ThreadLocal 归属语义；不能把 `pilferPointers` 改成长期全局禁止。构造器和动态发现的 hook 也必须通过 managedHook 纳入当前代际门控，旧 handles 在新代 onHotReloaded 中卸载。

### `StylusWindowGestureHook`：窗口标题栏与全屏角落

安装于 SystemUI 主进程的 Shell 类加载器。

- **窗口控制器：** 优先安装下述 `CaptionInputHook`，同时调整顶部三点和小窗底部白条的窗口输入及其原生 monitor；确定顶部基础 ABI 不支持时记录不可用并保留原 `MulWinSwitchDecorViewModel.handleReceivedMotionEvent(...)` 的通用 void 过滤。底部适配单独校验，不可用时保留顶部适配。该过滤只能禁止控制器动作，不能保证实心窗口向下层透传。
- **角落自由窗口：** `com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeThermalArea.fullscreenHotSpotDetection(MotionEvent): boolean`；要求单参数及 boolean 返回。当前事件含笔且该 display 的 `CORNER` 位启用时直接返回 false，使后续任务转换拿不到全屏角落命中；其余情况 proceed。

角落是无状态命中谓词，按每个事件的当前配置判断，不使用 PenMotionStream 的 DOWN 快照；维护文档或测试时不要把两种路径混为同一状态机。第一次拦截角落时只记一次日志，安装结束报告 caption/corner 数量。

`StylusWindowGestureHook.install()` 最后调用 `SplitStylusGestureHook.install()`；分屏入口单独处理消费者语义，不调用通用观察器安装器。

### `CaptionInputHook`：顶部控制器与小窗底部白条的笔输入透传

文件：`runtime/gesture/CaptionInputHook.java`、`CaptionRoutingState.java`、`CaptionWindowFlags.java`、`CaptionWindowTransaction.java`。宿主仍是 SystemUI 主进程的 package ClassLoader，配置沿用 `ignoreStylusCaption`／`CAPTION`，不增加开关或作用域。

OTA 的 `MiuiBaseWindowDecoration.getLayoutParamsFlags(boolean): int` 在普通／实心状态返回 `8`，闲置约 3 秒进入原生 immersion 状态后返回 `24`。差值 `16` 是 `FLAG_NOT_TOUCHABLE`：半透明阶段窗口已经不接收触摸，而非原 void callback 把事件重新投给下层。原控制器的 View.OnTouchListener 对 handle 返回 true，因此只过滤 monitor 的动作回调仍会让实心窗口吃掉笔输入。

| managed hook 入口 | 精确限定与作用 |
| --- | --- |
| `MiuiBaseWindowDecoration.getLayoutParamsFlags(boolean): int` | 仅真实接收对象为已确认 `MiuiTopDecoration` 或可用的 `MiuiBottomDecoration` 时按已发布 routing 添加 `FLAG_NOT_TOUCHABLE`；其他 decoration 保持原结果 |
| `MulWinSwitchDecorViewModel.handleReceivedMotionEvent(MotionEvent, InputMonitor, boolean): void` | 三参数确定 ABI，要求实际 model、display receiver 和 monitor 身份匹配；使用 `window-caption` Origin 与 DOWN 策略过滤笔流 |
| `MiuiFreeformModeController.handleTouchEvent(MotionEvent, InputMonitor): MiuiFreeformModeTouchResult` | 底部可选 after hook，只记录当前 Input、同一 model 的 controller、同一 monitor、DOWN 和流身份匹配的原生返回；`touchMode=2` 及返回任务 ID 匹配才提供小窗底部归属，不能借用其他 controller／流 |
| `InputMonitor.pilferPointers(): void` | 仅当前 caption inputContext 的同一 monitor 且 Origin 为 blocked 的 `window-caption` 时阻止抢指针；原生已完成的 capture 与模块新增 capture 分别记录 |
| model 的全部声明构造器 | 原构造成功后发现真实 model／Handler、配置 Context 和已存在窗口 |
| base `releaseViews(): void` | 仅已确认顶部／底部 decoration 释放时，先尝试取消模块拥有的控制器按压；底部原生 override 调用该 super 入口；失败记录 pending／诊断，仍放行原窗口资源释放，不阻挡宿主销毁 |
| model `removeEventReceiver(): void` | 原方法成功删除全部 display receiver 后，清除旧 routing／取消身份并尝试释放模块 Press；不由模块销毁原生 monitor |

开启时，在实际宿主 Handler 上枚举已有和新建 `mTopDecoration`／`mBottomDecoration`，协调 LayoutParams／ViewHost relayout；原始笔流由系统窗口命中落到下层应用，不合成或补发笔迹／点击，也不向系统输入队列注入 MotionEvent，不修改原生半透明动画及 immersion 状态。取消副本只用于后述模块持有的原生控制器清理。非笔 DOWN 命中实际实心控制器窗口、该窗口的不可触摸位确由模块添加且原生尚未抢指针时，模块通过该 monitor 提前 pilfer，避免控制器动作与完整下层点击同时完成。闲置首次触摸或已由原生 pilfer 的流不追加模块停止权。

底部适配是同一 `CAPTION` 选项的覆盖扩展，不增加配置、作用域或生产文件。`MiuiBottomDecoration.updateViews()` 的原生 flags 参数是 immersion、分屏或 Home／SoSc 的组合；记录真实 `getLayoutParamsFlags()` 调用的原始返回，晚发现的窗口首次以实际 LayoutParams.flags 建立基线，不能只用 `mIsDecorationImmersive` 重新推算并清掉原生的 `NOT_TOUCHABLE`。

小窗底部没有可安全复用的公开命中谓词。使用原生返回 `getTouchMode()==2` 和继承自 `MultiTaskingBaseTaskInfo` 的 public int `mTaskId` 匹配 `mTouchedFreeformTaskId`；顶部模式为 1，缩放角落等模式不能认作底部。原生底部热区包含窗口外的 HotSpace，仅在 `isInAnimating()==false`、目的边界／`mDestinationScaleX` 与真实 BottomView 宽高给出的窗口矩形内才新增手指停止权，动画中或热区外延不新增。分屏底部使用 `mTouchedBottomTaskId` 和 `isTouchedInSplitBottomCaption(MotionEvent): boolean`，不能把该分屏坐标谓词用于小窗，也不混入独立的分屏中间 `DividerView`。

底部原生本地 OnClickListener 维护三击提示。模块拥有的小窗 Bottom 按压正常 UP、原方法成功、无 `FLAG_CANCELED` 且原生 `mMultiTaskingTouchStatus.isDragging()==false` 时调用原 BottomView.performClick 一次；不替换监听器，也不重新触发全局单击／双击／拖动动作。已由原生取得停止权、闲置首次触摸、取消和拖动不补本地点击。

`CaptionRoutingState` 按 model／display 保存已发布策略及每设备 downTime。设置或焦点变化在活动流结束后协调，拒绝错配 UP、旧 DOWN 和重复提交；设备移除只清该设备的活动身份，保留其他设备及已发布值。`CaptionWindowFlags`／`CaptionWindowTransaction` 仅拥有自己添加的 `FLAG_NOT_TOUCHABLE` 位，原生本来存在的位不能被退出清除；外部 flags 变化不覆盖，relayout 失败保留失败状态并尝试补偿，routing 仅在应用成功后发布。

仅模块取得停止权的 Press 保存最后事件副本，用于混合流、替换、设备移除和退出时的原生 CANCEL。原生自己的 capture 不登记为模块 Press。输入设备监听在 MAIN 注册，设备移除后的取消、routing.forgetDevice 和取消身份清理排入所属 model Handler；原生 removeEventReceiver 删除全部接收器后也清除过期 routing 并尝试模块取消，不自己销毁 monitor。取消失败保留 pending 并重试；旧按压三元组成为取消身份，防止迟到尾流复活。退出在实际 Handler 上尝试全部模块按压释放与自己添加位的恢复，失败拒绝热重载；不关闭原生 monitor 或 ViewHost。成功后只保存 `gesture.caption.models` 中的真实宿主 model 和 `gesture.caption.cancelled` 中的宿主对象／中性 Bundle，新代重建窗口状态，不遗留旧模块 listener、Press 或 Hooker。

监视器抢指针的模式仍可能让手指 DOWN 先到应用再由系统取消。不能把自动测试中的“没有完成应用点击”写成真机“从未收到 DOWN”，也不能声称 JVM 已验证跨窗口笔迹透传。

### `SplitStylusGestureHook`：分屏操作条与菜单

文件：`runtime/gesture/SplitStylusGestureHook.java`、`SplitTouchFilter.java`、`SplitDividerCancellation.java`、`SplitDividerInputHook.java`、`SplitDividerRouting.java`。安装于 SystemUI 主进程的 package ClassLoader，独立读取 `ignoreStylusSplitDivider`／`SPLIT_DIVIDER`，默认关闭。

| managed hook 入口 | 匹配与作用 |
| --- | --- |
| `com.android.wm.shell.common.split.DividerView.onTouch(View, MotionEvent): boolean` | 精确实例 ABI，receiver 必须是该已确认类，第一参数必须是 receiver；从第二参数读取事件，笔流返回 true，阻止拖动、点击唤出菜单和双击 |
| `com.android.wm.shell.common.split.MiuiSplitMenuView` 实际继承的 `dispatchTouchEvent(MotionEvent): boolean` | 精确 boolean 实例 ABI，首先要求 receiver 的实际 Class 等于已确认菜单类；其他接收对象立即 proceed，不读取配置。过滤在子控件分发前完成，覆盖已打开菜单的切换方向／交换位置按钮 |
| `SurfaceControlViewHost.setView(View, WindowManager.LayoutParams): void` | 只处理精确 DividerView、精确 SplitWindowManager、manager 的 mDividerView／mViewHost 实例关联、2034 类型及原生 trusted overlay。创建输入窗口前按配置添加 `inputFeatures` 的 SPY 位 4 |
| `SurfaceControlViewHost.relayout(WindowManager.LayoutParams): void` | 只处理已登记的同一 host，保留模块拥有的 SPY 位及宿主其他 flags／features，协调写失败补偿 |
| `SurfaceControlViewHost.release(): void` | 只处理已登记的 host，取消模块取得停止权的拖动并恢复 SPY 位，再放行原生资源释放；不由模块销毁窗口或通道 |

菜单是独立输入窗口，不能只 hook DividerView 或菜单自己的 `onTouch(View, MotionEvent)`。继承入口的 hook 不替换宿主触摸监听器，不全局禁止框架 View 输入；每次 callback 都有 receiver 限定和会话门控。

单独让 `onTouch()` 返回 true 只会消费输入，不会透传。窗口路由规则：对已验证、具备 MONITOR_INPUT 权限的分隔条使用 `INPUT_FEATURE_SPY=4`，保留其原生触摸区域和 `PRIVATE_FLAG_TRUSTED_OVERLAY=536870912`。原始笔事件由 InputDispatcher 同时交付观察窗口和下层真实应用；模块不注入、复制重放笔迹，也不按中线坐标猜测目标。已打开菜单保持普通窗口，仍只屏蔽菜单笔操作；其在上层的原生命中会挡住下方分隔条 spy，不把菜单变成第二个抢指针者。

允许的独立非笔 DOWN 在执行原生 divider 之前，通过同一 host 的 `getRootSurfaceControl()` 取得实际 ViewRootImpl，调用 `getInputToken(): IBinder`，再用 `InputManager.pilferPointers(IBinder): void` 接管该通道。手指仍走原生拖动／点击／双击，模块只保存自己接管的按压用于失败、设备移除、窗口释放和退出取消。每个窗口的策略从真实 ViewRootImpl.getDisplayId() 读取焦点快照；配置／焦点变化等所有活动流结束才修改窗口位，关闭时只清自己添加的 SPY，保留其他原生输入参数。

`pilferPointers` 作用于通道全部活动设备。已观察到笔按下时，随后进入的其他设备非笔流不会抢指针或操作分隔条；笔加入模块已接管的原生流时先取消拖动并吞其余非笔尾流。这个保护不证明尚未交付到 Java 的多设备排队 DOWN 没有竞态，也不能给已被取消的下层手指流补回完整笔 DOWN。手指捕获可能让应用先收到 DOWN 再收到 CANCEL；禁止把它描述为应用从未收到 DOWN。

`SplitTouchFilter` 按 View 身份隔离流，使用 `(downTime, deviceId)`，在 DOWN 固定配置及分应用策略。遍历全部 pointer 识别笔尖／橡皮擦；从初始 DOWN 拦截的笔流不运行原消费者，后续流事件持续返回 true。已放行手指流中加入笔时，使用最后一次放行事件的副本补一次 CANCEL，随后吞剩余流；副本在宿主改写坐标前保存，终止时回收。分屏使用默认策略 `cancelUnobserved=true`、`retainSuppressedCancel=false`：未取得原始 DOWN 的晚注入笔流先尝试取消原消费者，正常终止后移除流。侧边条使用单独的参数策略，不能把它的重定向尾流处理改成所有消费者的默认规则。

原生分隔条的 CANCEL 与 UP 共用终止路径，直接调用会在拖动后吸附。`SplitDividerCancellation` 先验证 `mMoving`、`mVelocityTracker`、`mSplitLayout`、`mDividerPosition`、`mResizingInMinimizedMode` 及 `updateDividerBounds(int, boolean): void` 的精确 ABI；取消时先将 `mMoving=false`，再通过 ORIGIN 调原生 CANCEL，使其走清理分支。若原流曾拖动，则调用 `updateDividerBounds(committedPosition, false)` 回到已提交位置，避免笔坐标／速度引发吸附或退出。原生清理后 tracker、moving 或 resizing 仍未释放则保持 pending，并继续消费输入、记录失败；后续事件、对应设备移除和会话退出会重试，不能把清理失败报告为退出成功。

输入设备监听在 MAIN 异步注册，取消在各 View 实际 Handler 的 Looper 执行。退出先取消模块 MAIN 任务并注销监听，尝试全部 pending 释放后保存 `gesture.split.divider`／`gesture.split.menu` 的 `Map<View, Bundle>` 中性状态：流身份、DOWN 策略、suppressed 与真正的 MotionEvent 副本。新代重新构建状态并回收保存副本，不保存旧模块 Stream、Canceller 或 listener。未由模块接管的原生手指拖动不在退出时擅自取消。

窗口适配器比基础过滤器后登记退出，因而先在所属 Handler 上取消模块捕获、恢复自有 SPY，再让过滤器保存状态。另保存 `gesture.split.windows` 的 `Map<View, Bundle>`：仅真实 View、已取消的 device/downTime 和未被模块接管的原生活动身份。新代继续等待原生活动流 UP 再加 SPY，不能在关闭配置时的原生拖动跨代后吞掉它的 UP。失败保留取消及不确定写状态，拒绝静默退出。恢复窗口逐个隔离错误，安装整体失败则停用已安装回调并尝试补偿，避免只剩 spy 而没有非笔捕获。

首次晚注入已有窗口只在输入入口发现该窗口，当前 DOWN 已完成目标选择；该次只能保留原过滤，后续新流才可使用 spy。需要重建分屏或加载新版 SystemUI 以验证首笔。真正位于两应用窗口之外的中缝没有下层应用目标，不制造跨窗口笔迹。路由回归为 `SplitDividerRoutingTest` 和 `SplitDividerInputHookTest`；模拟宿主及 JVM 测试不证明真实 InputDispatcher 已交付笔迹。

源码实现、自动测试和目标设备实测分别报告，不从类签名存在推断新版设备已验证。

### `StylusSidebarHook`：禁止触控笔操作侧边条

文件：`runtime/gesture/StylusSidebarHook.java`、`SidebarTouchCancellation.java`、`SidebarMotionRedirect.java`。依据平板管家 `13.6.2-260922.0.1.pad`，宿主是 `com.miui.securitycenter:ui`，使用该包实际 ClassLoader；独立配置为 `ignoreStylusSidebar`／`ignore_stylus_sidebar`／`SIDEBAR=128`，默认关闭。全局功能只需要平板管家作用域，生效的分应用覆盖另需现有 system 焦点。

| managed hook 或发现入口 | 精确限定与作用 |
| --- | --- |
| `com.miui.dock.sidebar.p` 的声明构造器及 getter `w(): com.miui.dock.sidebar.e`／`y(): RegionSamplingImageView` | 原方法完成后登记该真实 wrapper 的 cover 与条视图；getter 发现回调内通过 ORIGIN 读取，避免自身递归 |
| 登记 cover／条视图实际声明或继承的 `dispatchTouchEvent(MotionEvent): boolean` | 实际 Class 必须等于已确认 `com.miui.dock.sidebar.e` 或 `RegionSamplingImageView`，再按实例映射和实际入口 Method 校验；其他 View 立即 proceed，笔尖／橡皮擦整条侧边条触摸流返回 true |
| 同实例 `dispatchGenericMotionEvent(MotionEvent): boolean` | 配置开启且事件含笔尖／橡皮擦时消费悬停等 generic 输入；手指／鼠标正常分发 |
| `com.miui.gamebooster.service.DockWindowManagerService.onCreate()` | 原方法完成后读取确定字段 `d: vb.l0`，发现其真实 sidebar wrapper；晚加载通过 ActivityThread 的实际服务表读取同一服务，不启动新的宿主服务 |

侧边条开关仅禁止操作条本身，展开的应用菜单保持原生笔操作。运行时登记 `p.w()` 的真实 cover 与 `p.y()` 的 RegionSamplingImageView 条视图；后者是 `sidebar_background`，直接绑定原生 `k` 条触摸监听器，cover 的 `p.h0()` 也向它委托。手指展开后直接进入条视图的笔事件必须继续过滤，否则会绕过 cover 再次开栏。不登记 `p.B()` 的完整面板，也不拦截 `p.C()` 的 TurboLayout、应用列表或快捷控件。即使它们与条视图继承同一框架 dispatch 方法，未匹配的实际 Class／实例也立即 proceed。`B(): FrameLayout` 只用于保存条视图实际窗口关联。动态发现的方法同样通过 `managedHook` 安装。该版本 manager 的 wrapper 字段为 `e`、`f`、`l`，扫描要求非 static、确定 wrapper 类型及真实对象。

触摸复用 `SplitTouchFilter`，按 owner、deviceId、downTime 隔离，DOWN 固定全局／分应用策略。每个 Binding 记录实际 touch／generic Method；Region override 调用父类 View 分发时，父类 hook 不能再次运行过滤或 pending 取消，避免重复消费和取消递归。Region 实际继承的 generic Method 仍应过滤。侧边条使用 `cancelUnobserved=false`、`retainSuppressedCancel=true`：只为已经放行过 DOWN 的活动流补原生取消，未观察到 DOWN 的孤立笔 MOVE／UP 仅消费，不启动宿主清理。已抑制流收到 CANCEL 后回收事件副本，但保留身份与抑制状态，直到匹配 UP、新身份 DOWN 或设备移除，避免原生重定向取消窗口后迟到尾流重新进入宿主。中性快照继续保留这些原始值，新代不能恢复为未抑制流。

手指流中加入笔时需要一次原生取消；失败保留 pending 并继续消费，后续输入、对应设备移除和退出重试。侧栏触摸 listener `com.miui.dock.sidebar.k` 把 CANCEL 与 UP 共用收尾，不能直接按普通抬起处理：`SidebarTouchCancellation` 在原按压 downTime 匹配时先设置 `n=true`，从实际队列 View `q` 移除延迟任务 `D`，调用原始 CANCEL，再执行 wrapper `P()`、listener 的移动清理 `v()` 和结束清理 `u()`，清空但不销毁宿主 VelocityTracker `g`；不调用会重定向／吸附的 `t()`。downTime 不匹配时整个取消回调直接结束，不调用原始 CANCEL 或 `P/v/u`，不能把旧手指状态当成新笔流的清理目标。清理在实际 View 所属 Handler 上执行并汇总失败，不把未完成取消报告为热重载成功。

`SidebarMotionRedirect` 只在真实系统平板管家 Context、已确认 native 服务可用、侧边条窗口类型为 `2026` 且原有 `miuiFlags & 32` 时，为 cover 收到的初始笔 DOWN 按其原生 `MotionEvent.getId()` 调用 `MiuiInputManager.requestRedirect(int): void`；ID 的负号源位保留，只有 `-1` 无效。条视图的窗口关联是实际 `p.B()` 面板，不能借用 cover 的参数重定向；直接条视图事件和展开面板的 `2003` 窗口不进入重定向。同一按压至多请求一次，不修改宿主 flags、不制造或重新注入笔事件。void 请求没有实际交付回执；不具备原生重定向条件的侧边条窗口仍仅禁止操作，不能承诺输入穿透到下层应用。

`sidebar-config` 经 `HostLifecycle` 启动配置、焦点和输入设备监听。退出注销模块监听，尝试全部 pending 取消，并保存 `gesture.sidebar.wrappers` 的真正宿主 wrapper 与 `gesture.sidebar.streams` 的中性流快照；新代恢复后重新登记，不保存旧模块 Binding、Canceller 或 listener，不销毁宿主服务和窗口。

### `FocusNotificationGestureHook`：禁止笔横滑唤出隐藏的超级岛

安装于 SystemUI 主进程，实际动作入口位于独立超级岛插件 ClassLoader。这项设置的现有存储键保留，但语义应准确描述为“禁止触控笔横滑唤出超级岛”。

#### 插件发现 hook

- hook `android.view.ViewGroup.dispatchTouchEvent(MotionEvent)`：检查真实接收对象的父类链是否包含 `miui.systemui.dynamicisland.window.DynamicIslandWindowView`，找到后安装其 ClassLoader 中的插件 hook；始终原样 proceed，不在窗口总入口吞事件。
- after-hook `com.android.systemui.statusbar.notification.DynamicIslandPluginController.onPluginLoaded(...)`：仅第一个参数非空时，用该插件实例的 ClassLoader 安装插件入口。
- `Set<ClassLoader>` 防止同代重复安装。新代会恢复 `focus.plugins` 中已有插件 loader，以便热重载时不等待插件重新加载；退出前保存当前 loader 列表。

#### 插件入口与动作 hook

以下类名均以 `miui.systemui.dynamicisland.` 为前缀：

| 类及方法 | 拦截逻辑 |
| --- | --- |
| `touch.domain.interactor.DynamicIslandTouchInteractor.onInterceptTouchEvent(MotionEvent, String)` | 观察源频道、流身份、笔类型、策略和岛可见状态，在 Scope 中调用原方法 |
| 同类 `onTouchEvent(MotionEvent, String)` | 同上；intercept 与 touch 重复遍历必须共用原始流快照 |
| `event.DynamicIslandEventCoordinator.dispatchSwipe(float, float, boolean)` | 仅同 coordinator 的 Scope 标记 suppressReveal 且当前方向为 horizontal 时返回 null |
| 同类 `dispatchEvent(DynamicIslandEvent, window.content.DynamicIslandContentView)` | 仅同 coordinator 的 Scope 标记 suppressReveal 且事件类型为 `DynamicIslandEvent$SwipeLeft` 或 `$SwipeRight` 时返回 null |

interactor 中读取 `windowView`、`eventCoordinator.get()`、`direction.isHorizontal()`；反射检查失败时记录错误并放行原行为。四个插件方法全部通过 `managedHook` 安装，不能在插件加载后另用未纳入会话的 hook。

`isHidden(window)` 只有在 `getCurrentBigIslandState()`、`getCurrentSmallIslandState()`、`getCurrentExpandedState()`、`getCurrentTempShowBigIslandState()` 四者都返回 null 时才为 true。临时通知也属于可见内容；缺失任一 API 会抛出异常，由调用方放行，不能当成隐藏岛。

#### `FocusNotificationInputGate` 的独立状态机

- WeakHashMap 按 interactor owner 隔离，内部按 `(source 字符串, deviceId)` 隔离频道；Touch 固定 downTime、DOWN 时 enabled 和 hidden。
- 只接受 DOWN、UP、MOVE、CANCEL、POINTER_DOWN、POINTER_UP；悬停、无 DOWN 的孤立事件或错误 downTime 不会创建触摸手势归属。
- 仅新的 DOWN（没有旧流或 downTime 大于旧值）创建快照。同一 DOWN 经 intercept/touch 重复送达、终止后迟到的重复 DOWN 不可修改已固定策略或隐藏状态。
- ended 前累计 `pen |= 当前事件含笔`，笔离开但手指尚未离开的混合流继续拥有笔归属；UP/CANCEL 标记 ended，保留快照用于终止事件重复遍历。
- 候选条件为 DOWN 时启用、DOWN 时隐藏、流中出现过笔。真正 suppressReveal 还要求**当前**窗口仍隐藏，因此手势中途出现通知后仍可操作通知。
- `ThreadLocal<Dispatch>` 只标记当前同步原生调用及具体 coordinator；`Scope.close()` 恢复嵌套父 Scope。另一个线程、另一个 coordinator 和 Scope 外的通知更新不得继承该归属。

本功能从不吞窗口/interactor 原始 MotionEvent，也不调用通用 PenMotionStream 的 CANCEL 路径。必须保留横滑隐藏可见岛、点击展开、长按、悬停、岛内按钮、外部点击、竖向手势及 terminal 清理；手指和鼠标不获得笔归属。不要退回早期“封锁超级岛全部触摸”的宽泛行为。

主要回归：`FocusNotificationInputGateTest` 覆盖隐藏岛横滑、已显示岛的横滑隐藏、点击／竖向通行、手指鼠标、悬停、混合输入、策略固定、重复与迟到事件、owner/channel/device 隔离、嵌套 Scope、异常恢复和跨线程隔离；`FocusNotificationToolTest` 覆盖所有可见岛槽位及缺失窗口 API 时不误判隐藏。

### 手势维护与验证要求

- 改配置掩码或新增手势位时，同步修改 `GestureAppConfig`、`EnhanceConfig.gestureMask()`、UI 编辑和按应用 profile 校验；完整替换与 0 掩码语义必须保持。
- 改 observer 输入时验证笔尖、橡皮擦、多 pointer、手指先行后笔加入、UP/CANCEL、owner/device/display 隔离；改变返回值或补取消时检查宿主 InputEvent 接收器是否完成正常终止。
- 动态插件和派生类扫描要有去重，hook 必须归当前会话。退出时不要把 ThreadLocal、旧接收器或旧方法闭包带到新代。
- JVM/Robolectric 回归能验证纯状态机和反射代理，不代表已验证 HyperOS 真机消费者；实际宿主签名、插件 coroutine 后同步 dispatch、输入所有权和窗口切换行为应由目标设备验证。

## 原生轻捏／双击设置所有权：`runtime/nativegesture`

`SystemBrushSettings` 与 `SystemBrushController` 不安装额外 Xposed hook。它们由 DisplayContent 焦点发布和热重载 resume 启动，修改当前固件实际消费的 Settings.System 设置，使真实 Pro 轻捏／双击仍由系统自己的输入路由处理。

### 系统设置与动作选择

`StylusGestureConfig.Kind.PINCH` 对应 `stylus_pinch_status`，默认 action=5，合法范围 0..5；`DOUBLE_TAP` 对应 `stylus_double_click_status`，默认 action=1，合法范围 0..4。两者各自拥有 enabled、globalAction、selected 和 app_actions，独立接管／退出。已选应用使用其动作，没有应用动作则回到该手势的全局值；取消选择保留编辑内容。

工作线程为模块创建的 `HyperStylus-native-gestures`。当前 Android 用户通过 `ActivityManager.getCurrentUser()` 查询，前台包名来自 WMS 顶层焦点。配置 ready 后、焦点变化后、Settings 内容变化后、真实 system UID 的 USER_SWITCHED 后和每 30 秒触发合并 reconcile；用户不可用时跳过写入。Settings 读写使用明确的用户 ID，并在 finally 恢复 Binder identity。

### `SystemBrushController` 的事务流程

所有权 Key 为 `(user, gestureKind)`，Claim 为 `(original, written)`。

1. 每次 apply 先释放该手势在其他用户上的旧 claim，或者在关闭接管时释放该手势的全部 claim。
2. 接管启用时，读取当前值。第一次接管记录它为 original；后续调整动作保留原 original，只更新 written。
3. **先提交 journal，再改 Settings。** Settings 写失败时保留 claim，以便后续 reconcile 重试或安全恢复；不得先改值再记所有权。
4. 仅在当前值与 desired 不同时写入。写入返回 false 视为失败，日志保留用户与手势信息。
5. release 时只在系统当前值等于本模块最后 written 的情况下恢复 original；若其他写入者已改成不同值，保留其值，再移除本模块 claim。
6. 原值可以为 null，不能用默认整数冒充用户原设置。`SystemBrushJournal` 用当前事务格式保存所有用户的 claim。

### 启动、退出与热重载

静态 initializing/startupId/retryAt 防并发启动；初始化失败等待 30 秒才可再次启动。线程创建后尽早登记清理，避免 Handler 构造或异步初始化失败留下线程。worker 退出取消任务、移除 ConfigBridge listener、注销 ContentObserver 和用户 receiver，保存 `brush.context`／`brush.foreground`，确认模块线程停止。

**普通热重载不释放 journal 的系统设置所有权，也不写回原值。** 新代重新读取 journal、配置和保存的宿主 Context 后继续 reconcile；用户关闭接管或切换用户时才按前述规则释放相应 claim。不要因为 reload 清理了 listener 就恢复系统手势，造成代际之间设置跳变。

显式 system 作用域移除调用 `prepareScopeRemoval()`：仅在当前配置 ready、revision 一致且 system 已无需求时启动／等待 worker，禁用 reconcile 两种手势并确认 `hasClaims()==false` 后回执。失败、配置改变、超时或代际退出均不确认成功；普通 close 的所有权保留规则不变。

对应测试：`SystemBrushControllerTest`、`SystemBrushRetirementTest`、`RuntimeReloadStateTest` 与 `SystemDataStorageTest`。重点包括多用户、独立手势、journal 在前、外部写入保护、初始化／注销失败和自建线程退出。

## 充电保护：`runtime/charge`

### `StylusChargeHook`：截获无线充电 HAL 与捕获宿主

- 运行宿主：`system_server`，由 `ModuleRuntime` 的 system 分支安装。
- 依赖确定的 ROM 类：`com.android.server.MiuiBatteryServiceImpl`、`miui.util.IMiCharge`。自建工作线程前先确认两类存在，线程名为 `HyperStylus-charge`；也有可注入 Handler 的构造入口。
- 真正的策略拦截点为 `IMiCharge.setWirelessChargingEnabled(boolean): int`；返回类型不符合 `int` 时安装失败，不猜测其他接口。
- 拦截流程：模块自己的 HAL 调用由 `ThreadLocal` 标记识别，直接 `proceed()`；原生调用持有 `WirelessChargeControl.lock`，先记录接收者与原生开/关意图。只有“当前已捕获 HAL + 原生要求开启 + 模块处于禁止充电状态”时，把参数改成 `false` 后 `proceed(new Object[]{false})`。原生关闭和其他 HAL 对象的请求保持原参数。真实调用完成后把请求与结果交给 `ChargeLimitRuntime.nativeCompleted()`，原结果照常返回。
- 宿主捕获都是 after hook：服务的所有构造器、精确的 `init(Context)`、`MiuiBatteryServiceImpl$BatteryHandler` 的所有已声明 `handleMessage` 方法。消息钩子仅在第一参数是 `Message` 且 `what == 12` 时，通过非 static、类型可赋值为服务的字段寻找外部服务实例。
- `capture()` 把工作投递到充电 Handler，读取服务 `mMiCharge` 和 `mContext`，记住 `charge.owner`、`charge.hardware`，绑定 HAL 后安装运行时接收器并评估策略。热重载会主动重新捕获保存的旧服务实例，因此不依赖构造器再次运行。
- 关闭时先退出策略运行时，再 `quitSafely()` 并等待自建线程最多 2 秒；从自身工作线程退出或线程未终止均报告失败，不能宣称重载成功。

### `ChargeLimitRuntime`、`ChargeLimitPolicy`、`WirelessChargeControl`：策略与所有权

这些类提供 hook 的实际逻辑，不各自安装新的方法拦截。

1. 初次附着读取 `/data/system/HyperStylus/charge_limit` 当前记录，恢复三个 bit：`blocked=1`、`ownsStop=2`、`nativeStopped=4`。注册 `RELAY` 与平板 `ACTION_BATTERY_CHANGED`/`ACTION_SHUTDOWN`；RELAY 先用 `getSentFromUid()` 与 `Protocol.from(..., Protocol.BLUETOOTH)` 校验真实蓝牙包 UID。监听 `Settings.Global` 的 `OPEN_WIRELESS_CHARGE` 与 `wireless_reverse_charging`，可选设置监听注册失败时按 3 秒间隔最多再试 6 次。
2. 配置 ready 后进入工作 Handler 更新配置、清零重试期限、评估并请求蓝牙快照。初次请求另在 3 秒和 15 秒重试，周期评估为 30 秒。
3. 通过发送者校验后，只接受 `snapshot`、`gatt`、`hall`、`Protocol.BATTERY`、`Protocol.SOC`、`Protocol.ATTACH` 来源。`ChargeLinkState` 按 `pen_connected`、连接时间、会话起始时间与 UUID 判断官方 BLE 会话；会话变化清空笔电量并重置充电周期。GATT 事件只更新会话并触发评估。快照的充电通道使用 `charge_battery`/`charge_battery_time`/`charge_state`/`charge_state_time`，不能以 UI 显示缓存代替。
4. 充电采样必须属于当前会话，电量 `0..100`、非未来且不超过 `60_000ms` 才能建立 `link.ready()`。一分钟上限由 `model/pen/ChargeLinkState.FRESH_BATTERY_MS` 定义，充电策略引用同一常量，模型不反向依赖运行时策略。`link.ready()` 表示当前会话曾获得有效采样，建立后不会随时间自行失效，直到断连或会话变更重置；自动新周期准入所需的新鲜电量仍由 policy 每次独立判断，同样只接受不超过一分钟的样本。原生电量广播或 `SOC` 且 `state==4` 是 live 电量，快照也可提供已确认的当前会话数据。充电状态还检查采样时间、6 小时通道上限和会话匹配。
5. 策略为滞回：默认开始 `50%`、停止 `80%`。`WAITING` 只由新鲜电量 `<= chargeStart` 转为 `ADMITTED`，允许完整周期继续到 `>= chargeStop`；已报告充电结束且电量高于开始阈值也回到 `WAITING`。未知/过期读数不能开始新周期，但已准入周期不会仅因低电量样本自然变旧就被撤销。停用充电限制清空普通周期并解除普通 blocked；通知显式操作独立于限制开关，按下文的本次策略处理。
6. 策略执行必须已有宿主 Context、配置与 HAL，session active 且未 shutdown。当前会话尚未确认有效采样，或 Hall 已明确取下笔时不直接 STOP，也不启用原生开启 veto。必要时，对之前本模块拥有的 STOP 进行恢复；不能将 ready 的会话确认与当前电量样本新鲜度混为同一条件。
7. RESTORE 要求确实由模块拥有停止权、原生未关闭、平板电量已知且健康为 `BATTERY_HEALTH_GOOD`、平板电量不低于 `wireless_reverse_charging`（默认 30）、`OPEN_WIRELESS_CHARGE` 非零、未 shutdown。原生 OFF 永远优先；模块不能恢复系统自身的低电量/健康/关机保护。
8. HAL 返回整数 `0` 才记作成功和更新 STOP 所有权。失败不认领停止权；命令通常按 30 秒限频，策略/配置变化或成功停止后出现新充电遥测可解除限频。命令后 1.5 秒读取 `getWirelessChargingStatus()` 记录实际 TX 状态。
9. `WirelessChargeControl` 用同一锁串行化模块和原生请求，保存各 HAL 对象的原生意图于弱键映射，模块调用使用 `ThreadLocal` 防止自身命令被重新 veto。关机后禁发新命令。只有 captured 原生请求的成功结果才更新策略所有权。
10. 热重载保存 policy、pen、link、control、上次命令、重试时间与成功标记为中性 `Bundle`；退出在工作 Looper 上持久化所有权、取消任务、注销遥测／平板 receiver 与 ContentObserver，关闭通知及操作 receiver、解绑 HAL。保存的 owned STOP 在新实例中重新核对，原生 OFF 与模块 STOP 始终分离。

维护要点：不要把“电量显示有效”与“允许恢复充电”合并；不要以缺少电量推断断连；不要绕过官方 BLE 会话；不要在清理旧代时擅自开启充电。

### `ChargeNotifications`：两类通知与本次充电操作

- 两个默认关闭的独立开关位于“电量与充电”。充电通知提供 STOP／FULL（停止充电／本次充满）；吸附未充电通知在低于停止上限时提供 START（本次开始充电），达到上限后改为 FULL；FULL 生效时主按钮为 CANCEL_FULL，但实际充电达到截止值时优先使用下述仅 STOP 规则。通知由 system_server 的标准 NotificationManager 发布，低重要性渠道和同一通知 ID，文本及原有 `ic_pen_native` 矢量通过模块包 Context 加载。
- 未充电且设备电量低于系统 `wireless_reverse_charging` 门槛时，优先显示当前设备电量、实际门槛及暂时无法开始充电的提示；随电量／系统设置变化更新，到达门槛后移除。门槛提示不绕过现有恢复条件，自动下限准入仍使用一分钟采样规则。
- 充电限制开启、实际正在充电且已知电量达到或超过 `chargeStop` 时，只显示“停止充电”，不显示 FULL／CANCEL_FULL；电量未知或限制关闭时不套用此条件。其他 FULL 生效状态显示“取消本次充满”；系统暂时停充时，已开启的吸附未充电通知也保留取消按钮。进入 FULL 保存此前 OneShot 与普通周期，取消恢复它们并按当前配置重新评估：已到正常上限则停充，已准入且尚未到上限继续原周期。取消不要求新鲜电量，不绕过系统保护。`CANCEL_FULL` 仅作命令，不保存为活动模式；原策略信息随中性 Bundle 热重载保存，取下／断连、STOP 或达到本次目标后清除。模式切换以及达到／低于停止上限引起的主操作切换都会撤销旧按钮凭据，显示和接收时共用 `primaryAction()` 判断；无效状态不暗中转换为其他操作。手动操作日志记录接受／拒绝原因、采样年龄、停止权及设备电量／健康，不记录 token 或设备地址。
- `ChargeLimitRuntime` 在同一 worker 消费真实蓝牙 RELAY 的 Hall 吸附通道，保留时间顺序和当前会话验证。充电通知需要当前连接和有效充电状态；未充电通知额外要求明确 Hall 吸附、未充电采样不早于本次吸附。未知状态不猜测，明确取下笔撤销通知、清空本次策略并解除 veto。
- `ChargeLimitPolicy.OneShot` 独立于普通上下限：STOP 持续禁止至本次吸附结束；FULL 目标 100%；START 跳过下限，到设置的停止上限，限制关闭时为 100%。达到目标后维持停止；已在上限以上时 START 不恢复供电。通知手动开始／充满要求当前官方会话已 ready，且存在属于该会话、非未来、未超过模型 6 小时有效期的已知电量；不套用自动下限准入的一分钟门槛，也不重写采样时间或使用 SystemUI 缓存。停止目标使用仍有效的已知电量，避免达到上限后因样本超过一分钟而误恢复。未知／过期／跨会话电量仍拒绝。HAL 成功不伪造成真实充电遥测。
- 取下笔、BLE 断连／会话变化、两个通知开关全关时清除本次操作；只关一个开关撤下对应通知并保留另一开关支持的已有操作。普通热重载在 policy 的中性 Bundle 保存 OneShot；不扩充 SCH1 磁盘格式。仍只恢复模块自己的停止权，原生 OFF、健康／低电和系统开关优先。
- 按钮使用限定宿主包的 immutable PendingIntent 和非导出 receiver，随机 token 绑定当前显示状态及代际，只接受该状态实际提供的命令并在接收后消费。状态切换、关闭、取下／断连和退出时撤销旧按钮。receiver 的全部操作经 `module.safely()` 门控，在执行前检查 MAIN 是否已经发布更新配置。
- 退出在原充电 Looper 撤下通知、撤销 PendingIntent、注销操作 receiver，清理失败参与会话失败聚合；不在退出时开启供电。通知不用新的 ROM hook、Manifest 组件或设置应用通知权限；任一开关申请 system／小米蓝牙，不额外申请 SystemUI。
- 


对应测试：`ChargeLimitPolicyTest`、`WirelessChargeControlTest`、`RuntimeReloadStateTest`、`BatteryStateTest`、`SystemDataStorageTest`、`PenBatteryCacheTest`。覆盖滞回边界、过期电量、原生关闭优先、失败不认领权、会话更换与热重载状态等；单元测试不能替代真机 HAL 验证。

## 蓝牙遥测：`runtime/bluetooth`

### `BluetoothRelay`：观察原生广播并提供可信快照

- 宿主包及进程均为 `com.xiaomi.bluetooth`，其内部笔服务类位于 `com.android.bluetooth.ble.app.oobhelper`。
- 方法 hook：`android.app.ContextImpl` 所有已声明的 `sendBroadcast` 重载，after 执行，只处理首参为 `Intent` 且 action 是 `com.android.settings.stylus.STYLUS_BATTERY_NOTIFY`、`STYLUS_STATE_SOC`、`STYLUS_STATE_ATT`。原生广播完整发出后才复制 Intent 并交由主 Handler 汇总；不会替换广播或消费它。
- `start()` 用 `createPackageContext(Protocol.BLUETOOTH, 0)` 创建蓝牙包 Context，注册 `REQUEST` 与屏幕事件；所有电量、充电、Hall、连接、RSSI 聚合在主 Handler 中运行。
- 快照请求接收方由真实 UID 校验：system UID 且 `reply_to="android"` 可以得到发往 `android` 的回复；SystemUI 必须与其包 UID 相符；其余请求忽略。先 bootstrap 官方连接并发送 `source="ready"`；存在已记录电量事件时再发送完整 snapshot。`Protocol.send()` 使用显式包名、前台 receiver flag 与共享发送者身份。
- `battery` 是显示遥测，`charging` 是单独的充电保护遥测；新官方 GATT 连接/连接对象变化生成会话时间与 UUID，清空旧充电采样；断开结束会话。电量／状态／连接转发携带 wall-clock 通道时间、会话身份，并发往 `android` 和 SystemUI。RSSI 消息仅含其 source／读数／时间，只发往 SystemUI。
- RSSI 采样启用条件为：已有 Context、battery_enabled 与 signal_enabled 同时开启、屏幕 interactive、笔未吸附、`battery.visible()` 已确认连接。息屏、吸附、断连或任一显示开关关闭停止采样，不持有唤醒锁；充电遥测继续独立保留。
- 热重载保存 battery/charging/signal/Hall 与连接身份为 `bluetooth.state`，保存当前 GATT 句柄；退出注销 REQUEST 与屏幕接收器、取消主 Handler 任务，不 disconnect/close ROM 所有的 GATT。

### `PenBleObserver`：官方服务、GATT 回调与 Hall 回调

真实 hook 清单（均为 after）：

| 宿主目标 | Hook 方法 | 限定与行为 |
| --- | --- | --- |
| `MiuiBleOobHelperService` | 全部构造器、已声明 `onCreate` | 捕获当前官方服务、检查确定字段并 bootstrap；不创建服务 |
| 同上 | 已声明 `onDestroy` | 仅当前 owner 销毁时清除 active/connectionField、解绑 poller 并上报断开代码 `6` |
| Hall 回调具体类型：确定类 `f` 或服务字段 `r` 的实际 `UEventObserver` 子类 | 已声明 `onUEvent` | 先确认 callback 为本笔所有，读取 `POWER_SUPPLY_PEN_HALL3` 与 `POWER_SUPPLY_PEN_HALL4` 并转发有效传感器值 |
| GATT 回调具体类型：确定类 `a` 或服务字段 `X0` 的实际 `BluetoothGattCallback` 子类 | 已声明 `onConnectionStateChange` | 要求本笔 callback、至少三参；status 非零/STATE_DISCONNECTED 映射 `6`，STATE_CONNECTED 映射 `2`，其他中间状态忽略 |
| `BluetoothGattCallback` 基类及已发现的本笔 GATT callback 子类 | 已声明 `onReadRemoteRssi` | 必须三参 `(BluetoothGatt, int rssi, int status)`，callback 属于本笔且 GATT 同时为 active 与官方字段值才交给 poller |

- 完整服务名为 `com.android.bluetooth.ble.app.oobhelper.MiuiBleOobHelperService`，`a` 与 `f` 也在此包。服务的 GATT callback 字段必须是 `X0: BluetoothGattCallback`，Hall 字段必须是 `r: UEventObserver`；记录实际 callback 对象于弱集合，并为实际类安装一次 hook。`a` callback 的外部服务引用取字段 `a`。
- 官方笔电量 GATT 只能由 `PenGattSource` 读取服务非 static 字段 `e: BluetoothGatt`。字段不符返回不可用，不扫描其他 GATT 作为替代。
- 建连回调拒绝与当前官方 GATT 不同的对象，断连回调拒绝已被替换的旧 active GATT。读 RSSI 同时检查官方身份与 `BLUETOOTH_CONNECT` 权限。bootstrap 只查询已有 GATT 的 `BluetoothManager.getConnectionState(..., GATT)`，不会调用 `connectGatt()`。
- 热重载主动绑定 `bluetooth.service` 里的原生服务并保留已有会话身份；退出清空弱 owner、callback 集合和类发现集合，关闭 poller；不销毁原生服务/GATT。

### `RssiPoller` 与 Hall/Signal 模型

- `RssiPoller` 最多保留一个 outstanding `readRemoteRssi()`，正常回调后间隔 `1_500ms` 再读，超时为 `5_000ms`。申请被拒可按间隔重试；申请被接受但回调超时只标未知并等待已有请求完成，不叠加新读取。迟到、非当前连接、status 非零或无效 RSSI 不产生有效信号。
- 禁用不会取消蓝牙控制器已发出的读操作；重新启用仍保留 outstanding 身份。更换 GATT 清除旧请求，旧 callback 不能污染新连接；close 取消 timer 并清空对象。
- `HallState` 为 active-low：任意 Hall 值 `0` 证明吸附；两者都为 `1` 才证明离开；缺失或未知传感器值不能推断离开。
- `SignalState` 合法 RSSI `-127..20`，有效期 `10_000ms`，与电量和连接分开排序。默认 `-80dBm` 起一格、`-60dBm` 起两格，低于一格阈值显示 0 格，未知返回 `-1`。
- 对应测试：`PenGattSourceTest`、`RssiPollerTest`、`BatteryStateTest`、`RuntimeReloadStateTest`。

## 状态栏与控制中心：`runtime/battery`

### `SystemUiController`：宿主初始化、显示模型与重绘

- 宿主 SystemUI，槽位固定为 `bluetooth_stylus_battery`；构造时可恢复 `systemui.telemetry`、`systemui.controller` 与 `systemui.views`。
- after hook：`com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl` 的全部构造器，投递主 Handler 捕获实例；同时安装 `IconSlotOrder`、`PenStatusViews`、`IconVisibility` 子钩子。
- 重载/进程已存在时从保存的 controller 或 `com.android.systemui.Dependency.get(StatusBarIconController.class)` 主动获取，未获得每秒重试，最多约 120 次。
- `start()` 初始化 SystemUI 私有 `SharedPreferences("HyperStylusBattery")` 的 `PenBatteryCache`；配置同步后设置区域策略、建立本地 InputDevice/Bluetooth 监听、校验蓝牙启动状态，再注册蓝牙 RELAY、adapter 状态与 screen/unlock 生命周期。
- 电量 RELAY 验证发送者属于蓝牙包；RSSI 仅在连接且未吸附时接受，否则 invalidate。连接、Hall、电量、充电分别保留时间顺序；断开或 adapter 关闭隐藏图标并清除信号，但最后有效电量仍保留。
- `PenBatteryCache` 只接受合法 `0..100`、有时间且非未来的成功样本，保存原采样时间；失败、未知、旧样本不覆盖。显示层没有 6 小时过期截断，因此隔夜可显示旧电量，但必须有当前连接才能显示图标。缓存不会产生新的充电控制采样。
- `BatteryState` 本身采样新鲜度为 6 小时；连接可见性仅取 `connectionKnown && !disconnected`，不因电量过期推断断连。连接码 `2/5` 表示连接，`0/1/3/4/6/7` 表示断开。充电码只接受 `2/4`。Hall 真值优先，只有 Hall 未知时才由充电临时推断吸附。 `attachmentChangedAt` 单独保存实际 Hall 状态变化时间，重复同值上报仅更新 `lastAttachmentAt`，避免通知把仍然吸附误判为重新吸附。
- `WakeRefresh` 对 SCREEN_ON 与 USER_PRESENT 立即刷新本地连接、寻找 controller、重绘视图并请求 snapshot，另在 3/15 秒重试；重入 wake 合并到最新一轮，SCREEN_OFF/close 取消这一轮。不增加持续的亮屏网络轮询或唤醒锁。控制器另有启动快照 3/15 秒重试与每 60 秒显示 render tick；RSSI 定时器按其独立 10 秒期限刷新。
- render 只有 session active、Context/controller 可用时执行；真正 visible 还要求 battery 功能 enabled 与已确认连接。视图批量 refresh 通过主 Handler 合并。
- 退出在主 Looper 取消任务、关闭 WakeRefresh 与 PenConnectionMonitor、注销三个 receiver、持久化缓存并保存中性 telemetry、controller、views；移除 attach listener，供新代重新恢复并安装。

### `IconSlotOrder`：构造前插槽与安全迟注册

- before/proceed hook：`com.android.systemui.statusbar.phone.ui.StatusBarIconList(String[])`，把原数组中的同名 stylus 槽去重并插在第一个 `bluetooth` 后，再调用原构造器。无 Bluetooth 槽时返回原内容克隆；数组准备抛异常时记录错误，保留原参数执行原构造器。
- 已存在 controller 通过 `mStatusBarIconList.mSlots`、`findOrInsertSlot()` 查找/加入槽位。只有 holder-free 空槽能移动到 Bluetooth 后；`hasIconsInSlot()==true` 时抛出需重启 SystemUI 的错误，避免移动已经对应原生视图索引的槽位。

### `PenStatusViews`：每个图标的宽度、颜色与原生事件重绘

所有目标为 `com.android.systemui.statusbar.StatusBarIconView` 的已声明方法，都是 after hook；所有处理先以 `getSlot()` 检查仅属于 stylus 槽。

| 方法 | 功能与流程 |
| --- | --- |
| `set` | 原模型应用后 `configure()`：FIT_CENTER、`mIconScale=1f`、按本视图颜色绘制、应用区域可见性并安装 attach listener |
| `maybeUpdateIconScaleDimens` | 原生更新缩放后恢复 `mIconScale=1f`，避免动态宽度位图二次缩放 |
| `onDarkChanged` | 第三参为 Integer 色值时，优先调用 `DarkIconDispatcher.getTint(args[0], image, supplied)` 得到所在区域色，再重绘 |
| `setStaticDrawableColor` | 首参 Integer 为该视图新颜色，重绘，适用于控制中心静态着色 |
| `updateIconColor` | 原生着色后用该视图保存的颜色重绘；`painting` 防重入 |

- 颜色与监听使用弱键映射，初始 tint 可取邻近图标，否则白色；透明色以白色替代。paint 清除原生 tint/filter、设置新 Bitmap，并让 LayoutParams.width 与实际位图一致。reload detach 移除所有 attach listener 并保存 view→color 中性映射，新代 restore 后重新 configure。

### `IconVisibility`：分别控制状态栏与控制中心

| after hook 目标 | 方法 | 作用 |
| --- | --- | --- |
| `com.android.systemui.statusbar.phone.ui.IconManager` | 已声明 `addIcon`、`onIconAdded` | 捕获 `mGroup`/区域，重新应用所属笔图标策略；返回值为本笔 ImageView 时立即 apply |
| `StatusBarIconView` | 已声明 `setBlocked` | 原生 block 变动后重新应用本笔区域规则，`updating` 防自身递归 |
| `com.android.systemui.statusbar.views.MiuiStatusIconContainer` | 已声明 `setIgnoredSlots` | 原生 ignoredSlots 变化后刷新本笔子视图，允许显示时移除本笔 ignored 槽并 requestLayout |

- 区域优先按 manager `mLocation` 的 enum 名 `QS` 判断控制中心，否则按 `mBlockList` 与 `MiuiIconManagerUtils.CONTROL_CENTER_BLOCK_LIST`、`RIGHT_BLOCK_LIST`、`MINI_RIGHT_BLOCK_LIST`（排除本笔槽后的内容）判断；默认状态栏。视图按祖先容器推导区域。
- 模式稳定值为 `0=STATUS_BAR`、`1=CONTROL_CENTER`、`2=BOTH`。battery disabled 时两区均 blocked；修改的是上述 ROM 列表中本笔槽的成员关系，不影响其他槽。只在 ArrayList 形态列表上修改。

### `PenStatusPresenter`、`IconHolderAdapter`、`PenBatteryIcon`

三者为 SystemUI hook 的模型/绘图依赖，不安装新方法 hook。

- Presenter 构造当前 ROM 的 `StatusBarIcon(UserHandle,String,Icon,int,int,CharSequence,StatusBarIcon.Type)`，Type 为 `SystemIcon`；提交 `controller.setIcon(slot,holder)` 与 `setIconVisibility(slot,...)`，附带电量、充电、信号无障碍描述。
- 当前 `com.android.systemui.statusbar.phone.StatusBarIconHolder` 没有声明构造器，只接受精确字段 `icon/type/tag/networkSpeedState` 与确定类型。通过 API 102 的 `Object.<init>.newInstanceSpecial` 分配，再初始化 `type=0`、`tag=0`、networkSpeed=null。不能补造不存在的构造器或以随意分配替代；布局不符拒绝使用。ClassValue 可在 session close 清缓存。
- 图标使用八个保留的 XML 矢量资源：笔、普通/低电框和五档填充。电量格 `ceil(level/20)` 限定 `1..5`；未知画短横线。充电黄色优先于低电红色。信号开启且笔未吸附时宽 25dp、画两条波；吸附或信号关闭时宽 20dp，不改变笔/电量原几何，高 20dp。showSignal 独立于真实 attached 状态，缓存键也含此值；关闭时不绘制淡波、不输出信号待更新描述。
- 模块资源必须从模块包 Context 读取，不能把模块 `R.drawable` ID 当作 SystemUI 资源 ID。Bitmap frame 使用 256KiB LruCache，返回只读共享帧；view 更新需重新设置原生宽度与 tint。
- 对应测试：`CurrentIconModelTest`、`PenBatteryIconTest`、`ModuleResourceLookupTest`、`WakeRefreshTest`、`PenBatteryCacheTest`。

### `PenConnectionMonitor`：本地连接证据

这是监听器而非方法 hook。注册 InputManager InputDeviceListener，按 `StylusDevices.version()>0` 与设备 BluetoothAddress 确认笔；监听 ACL、HID 状态、解除配对事件。蓝牙地址首先匹配 `Settings.Secure.miui_bluetooth_store_pen_address`，缺失时仅匹配已正面识别的笔设备地址；不根据名称猜笔。输入设备集合/地址可用性变化或 wake refresh 会重新扫描，close 注销 listener 与广播 receiver。

## 原生 Pro 设置入口：`runtime/prosettings/SecurityCoreHook`

- 宿主为 `com.miui.securitycore` 主进程，配置仅为 `proSettings`；构造时取 ConfigBridge 当前值，宿主 Context 获得后订阅配置。
- 唯一 hook 目标是 `android.view.InputDevice` 已声明且名为 `isXiaomiStylus`、返回 `int`、签名为无参或 `(int,int)` 的查询。没有额外 Settings Activity 替换，也不劫持输入设备状态。
- 每次先 `chain.proceed()` 获取真实代数，仅开启入口功能且原结果为正整数时返回 `8`（Pro generation）；返回 0/负数/异常或功能关闭时保持原行为。真实设备不是笔时不会伪造成笔。
- 目标方法的数量由当前 ROM 实际声明确定，安装失败按方法记录并继续；所有拦截由 managedHook 执行代际门控；旧代退出停止模块工作，新代 onHotReloaded 卸载旧 handles，配置 listener 由 ConfigBridge 统一收回。

## UI 与框架服务维护约定

设置应用使用 miuix Compose。`SettingsActivity` 持有统一 `EnhanceConfig`，页面通过 Bundle 编辑后自动保存；onResume 重新加载配置、更新应用目录、刷新框架状态并 publish 已保存快照。不要在 UI 复制一套与 Provider／hook 不一致的配置校验或默认值。

- 主页为状态、设置、关于；`SettingsNavigator` 管功能路由。三个主页使用 HorizontalPager，导航选中、左右滑动、返回和恢复需同步。
- 应用显示名称统一 `HyperStylus`，作者为 `星澪Star_ZER0`。`ui/screens/AboutScreen.kt` 使用 `about_strings.xml` 中的个人主页 `https://star0.cc`、GitHub 主页 `https://github.com/Star-ZER0` 和项目 `https://github.com/Star-ZER0/HyperStylus`；网页链接由浏览器打开，失败给出提示；联系区另提供 [QQ 群](https://qm.qq.com/q/jVEd9nftWE)入口，点击经 `AboutActions.joinQQGroup()` 使用提供的 key 唤起 QQ 加群界面，未安装或不可启动时回退打开 https://qm.qq.com/q/jVEd9nftWE，两种方式均失败才显示提示。QQ 客户端从 Activity 内启动时不添加 NEW_TASK，以保留返回路径。作者头像位于 `drawable-nodpi/star_zer0.jpg`，可导出的原始微信赞赏码位于 `raw/tipcode.jpg`，均使用用户提供的 JPEG，应用启动图标仍为 XML。赞赏码在弹层内按原比例圆形显示，移除四角白底，内容可滚动；弹窗不重复显示作者名或保存后的微信操作提示，仅保留感谢语。“保存到相册”和“关闭”横向并排，保存按钮使用 miuix 主色突出。`ui/state/AboutActions.kt` 在 IO 线程通过 MediaStore 保存未经裁切的原始赞赏码，写完后才发布，失败删除待写条目；无需新增存储权限。
- 600dp 起使用 NavigationRail，功能页保留侧栏；窄窗使用贴底通栏原生 NavigationBar。保持安全边距、末项滚动空间及侧栏展开状态的窗口／状态恢复。
- 应用配置包含没有 launcher Activity 的已安装应用，以及已卸载但仍保存的规则。使用统一 AppCatalog／AppListPresentation 管名称、包名、搜索、系统应用筛选和排序。
- 应用菜单使用原生 CascadingListPopup，排序子菜单保留名称／包名、倒序及已配置优先；批量设置通过对应配置接口完成。不要把取消选择写成删除已有 profile。
- `ActionConfig` 仅支持当前动作标识和 `keyboard:<十进制KeyCode>`；裸 `keyboard` 只是编辑器分类，不是可执行动作。完整映射只能为 none 或合法目标键；从完整映射切回单击／长按保留六项原动作。
- `ModuleServices` 使用 libxposed service 的真实框架连接与 HookedTarget handle。`FrameworkConnections` 以更新的同名连接替换旧连接，展示按进程合并最新状态；reload 直接传框架原 handle，不能由 PID 重造目标。
- 热重载请求只取 STALE／FAILED 目标，异步回调归入对应进程，30 秒未收到结果显示 TIMEOUT 后允许用户刷新。保留框架的失败／不支持／进程退出等诊断，不把请求提交成功写成重载完成。
- 模块状态页独立于 `MainScreens.kt`，由 `ui/screens/ModuleStatusScreen.kt` 展示连接概览、进程版本、重载结果及每框架作用域。概览的“已连接”只说明框架服务连接，不代表功能全部可用；进程仍保留原始名称、PID、UID、所属框架及辅助进程标记。作用域授权按各 `ModuleScopeStatus.actual/desired` 单独呈现，不以多个框架的授权并集代替某一框架的真实授权。尚未取得每框架状态时标记待查询，所有功能关闭时明确说明当前无需作用域。
- 设置主页使用 miuix `SettingsGroup`／`ArrowPreference` 分为触控笔、手势与交互两组；装饰图标通过 miuix `Icon` 展示，使用 `MiuixIcons` 及电量／键盘 XML 矢量，共享的 `SettingsIcon` 只用于新入口和状态页，不替换应用 XML 图标。关于页提供作者链接、赞赏弹窗和 QQ 群入口；三个主页均预留底栏可视空间，共享页面组件的默认行为不变；赞赏弹窗按上述规则展示。

## 构建和验证

环境与命令见 [README](../README.md#构建)，回归范围与真机验证边界见下文。修改输入、配置、代际或 APK 发布产物时执行完整构建、回归和 Lint；APK 结构检查与密码学签名检查分别执行。

### Hook 相关回归索引

下表的测试类均在 `app/src/test/java/cc/star0/xposed/hyperstylus/` 中；类名代表可定位的现有验证范围，并不意味着所有 ROM 方法已在模拟环境执行。

| 修改领域 | 优先检查的现有测试 |
| --- | --- |
| 功能开关、共享作用域与恢复回执 | `config/FeatureSwitchConfigTest`、`ScopePolicyTest`、`ui/state/ScopeSynchronizerTest`、`ScopeRemovalGuardTest`、`runtime/keys/StylusKeySwitchTest`、`runtime/nativegesture/ScopeRemovalPreparationTest`、`ui/screens/FeatureSwitchesUiTest` |
| 进程安装范围与辅助进程退出 | `module/HostProcessPolicyTest`、`ModuleRuntimeProcessTest`、`ui/screens/ModuleStatusRowsTest` |
| 入口、作用域与共享配置 | `config/ModuleIdentityTest`、`ConfigurationProviderTest`、`CurrentConfigValidationTest`、`CurrentApplicationPolicyValidationTest`、`ConfigReaderRetirementTest` |
| 会话与 receiver 清理 | `module/ModuleSessionTest`、`ReceiverRegistrationTest`、`runtime/charge/RuntimeReloadStateTest` |
| 物理三键／完整映射 | `runtime/keys/PressGestureTest`、`FullKeyDispatcherTest`、`MappedKeyInjectorTest`、`CompleteKeyInjectionTest`、`StylusKeyRepeatTest`（生产 Handler／会话计时器）、`config/FullKeyConfigTest` |
| 悬停与 Pro／软件动作 | `runtime/keys/StylusHoverPointTest`、`runtime/keys/StylusActionDispatchTest`、`MappedProGestureTest`、`runtime/keys/MappedProFallbackTest` |
| 普通笔流与每显示器焦点 | `runtime/gesture/PenMotionStreamTest`、`GestureFocusSnapshotTest`、`config/CurrentApplicationPolicyValidationTest` |
| 顶部窗口控制器透传、输入位所有权与停止权 | `runtime/gesture/CaptionInputHookTest`、`CaptionRoutingStateTest`、`CaptionWindowFlagsTest`、`CaptionWindowTransactionTest`、`GestureFocusSnapshotTest` |
| 侧边条、原生重定向与第六宿主 | `runtime/gesture/StylusSidebarHookTest`、`SidebarTouchCancellationTest`、`SidebarMotionRedirectTest`、`config/SidebarConfigTest`、`ConfigurationProviderTest`、`ModuleIdentityTest`、`ScopePolicyTest`、`ui/screens/SidebarSwitchUiTest`、`GestureFocusSnapshotTest` |
| 分屏操作条与菜单消费者 | `runtime/gesture/SplitTouchFilterTest`、`SplitDividerCancellationTest`、`config/SplitDividerConfigTest`、`ScopePolicyTest`、`ui/state/GestureSettingsEditsTest` |
| 超级岛横滑唤出 | `runtime/gesture/FocusNotificationInputGateTest`、`FocusNotificationToolTest`、`config/FocusNotificationConfigTest` |
| 充电／原生关闭优先 | `runtime/charge/ChargeLimitPolicyTest`、`WirelessChargeControlTest`、`RuntimeReloadStateTest`、`ChargeNotificationPolicyTest`、`ChargeNotificationsTest`、`ChargeNotificationRuntimeTest`、`model/pen/BatteryStateTest` |
| 官方 BLE／RSSI | `runtime/bluetooth/PenGattSourceTest`、`RssiPollerTest`、`model/pen/BatteryStateTest` |
| 原生手势 journal 与线程 | `runtime/nativegesture/SystemBrushControllerTest`、`SystemBrushRetirementTest`、`storage/SystemDataStorageTest` |
| SystemUI 电量与资源 | `runtime/battery/CurrentIconModelTest`、`PenBatteryIconTest`、`ModuleResourceLookupTest`、`WakeRefreshTest`、`storage/PenBatteryCacheTest` |
| 设置应用与框架状态 | `ui/screens/CompleteKeyMappingUiTest`、`ModuleStatusScreenTest`、`ModuleStatusRowsTest`、`ui/state/ModuleProcessPresentationTest`、`GestureSettingsEditsTest` |
| 导航与应用选择 | `ui/navigation/MainNavigationTest`、`ui/screens/ApplicationSelectionPopupTest`、`ui/apps/AppListPresentationTest` |

涉及 hook 语义的回归，应验证真实边界：笔尖／橡皮擦／手指／鼠标、多 pointer、事件乱序、同键重叠、取消／失败释放、配置 revision、每显示器和多用户、旧 callback 迟到、退出异常及线程清理。新增测试要覆盖行为或修复场景，不仅复述实现步骤。

### 真机验证范围

当前界面和 API 102 热重载需独立进行 HyperOS 真机验证。JVM／Robolectric、Lint 和 R8 可验证项目逻辑及产物结构，无法证明固件签名、动态插件同步调用、HAL 行为或前台应用支持的软件 Pro 指令。

需要目标设备验证的改动，按影响范围检查相关路径：模块注入与实际进程、输入 DOWN／repeat／UP、激光松开、返回与系统栏、桌面抢指针、超级岛隐藏／显示与通知中途出现、窗口标题栏实心／闲置透传与手指原生控制／角落、分屏操作条与菜单／混合流取消、平板管家 `:ui` 侧边条禁止及展开菜单笔点击／拖动保留、原生重定向、多显示器焦点、官方 BLE 重连／吸附／隔夜唤醒、充电原生保护与阈值周期、关闭接管后的系统设置恢复，以及热重载中的任务／receiver／线程／状态恢复。

## 排查顺序

先看状态页中的目标进程和加载版本，再核对安装计数与初始化日志，然后检查配置 revision、事件身份、当前会话和退出结果。主要日志标签为 `HyperStylus`，本地连接监听还可搜索 `StylusBattery`。

没有实际目标方法、启动成功或有效采样时，不能从界面开关已打开推断功能正在工作。安装与基础配置见 [README](../README.md)，作用域和具体接口见本文相应章节。
