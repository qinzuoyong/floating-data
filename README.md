# 手机信息悬浮窗

> 实时监测电池温度与功耗的 Android 悬浮窗工具
> 版本: 1.81 | 最低支持: Android 14 (API 34)

────────────────────────────────────────

功能特性
────────────────────────────────────────

  [温度] 实时温度显示
         悬浮窗显示当前电池温度(℃)，使用 Android 标准 BatteryManager API 读取

  [功耗] 功耗监测
         显示整机实时功耗(W)，使用 Android 标准 BatteryManager API

  [自定义] 高度自定义
         可调节字体大小、背景颜色、字体颜色、透明度、圆角曲率

  [屏幕] 横竖屏智能适配
         悬浮窗在横竖屏下均可自由拖拽，边界智能钳位不超出屏幕

  [后台] 隐藏后台模式
         开启后按 Home/返回键自动移除任务卡片，防止被划掉(悬浮窗服务继续运行)

  [自启] 开机自启动
         支持开机智能判断: 仅当上次退出前悬浮窗开启时才自动启动

  [保活] 进程保活
       前台 Service(常驻通知) + 15 分钟心跳 + 1x1 不可见 Overlay + JobScheduler 兜底看门狗
       电池优化白名单状态提示 + 国产 ROM 自启动一键跳转，锁屏不易被杀

  [更新] 自动更新检测
         打开应用自动检测是否有新版本，可选升级或忽略

  [下载] App 内可视化下载
         点击「立即升级」在 App 内下载 APK，实时显示 0%~100% 圆形进度 + 百分比动画

  [锁定] 双击锁定
         双击悬浮窗可锁定/解锁拖拽位置，独立开关控制

  [主题] 主题外观选择
         支持跟随系统/浅色/深色三种模式，实时切换，Material Design 3 设计语言

  [家人] 家人位置共享
         输入同一个 6 位家庭码即可互相查看位置；自建信令服务器中继（无
         WebRTC/TURN，包体轻量）+ 百度地图展示；按需获取家人位置——先粗后精
         自动精化，室内自动启用高德 WiFi 指纹定位（10~30 米），保留上次位置
         时间与精度；成员可本地备注、可退出家庭；后台常驻借悬浮窗前台服务
         保活（无独立常驻通知），可单独关闭「允许家人请求我的位置」隐私开关

────────────────────────────────────────

下载地址
────────────────────────────────────────

https://gitee.com/qinzuoyong/floating-data/releases

https://github.com/qinzuoyong/floating-data/releases

────────────────────────────────────────

构建说明
────────────────────────────────────────

环境要求:
  - Android Studio
  - JDK 17+
  - Android SDK 36+

构建命令:
  ./gradlew.bat assembleRelease --no-configuration-cache
  # 模拟器(雷电 x86_64)联调时追加参数，带上 x86_64 ABI：
  ./gradlew.bat assembleRelease --no-configuration-cache -PdevEmulatorAbi

APK 说明:
  - 正式包仅含 arm64-v8a（Android 14+ 真机全覆盖）
  - 加 -PdevEmulatorAbi 后含 arm64-v8a + x86_64（仅用于模拟器联调，不发布）

一键发布（构建正式版 + 清理 + 打 tag + 推送 + 发布）:
  bash publish.sh "发布说明(可选)"

APK 输出路径:
  release/yongge.apk   （正式版 APK）
  release/mapping.txt  （R8 混淆映射，线上崩溃还原用）

技术栈:
  Kotlin         2.2.10
  AGP            9.2.1
  Compile SDK    36
  Min SDK        34 (Android 14)
  Target SDK     34
  Compose BOM    2026.02.01
  Material3      Yes
  协程           1.11.0

────────────────────────────────────────

使用说明
────────────────────────────────────────

  1. 安装 APK 后打开应用
  2. 开启悬浮窗权限
  3. 忽略电池优化
  4. [保活] 在「关于」页开启自启动（厂商设置），加入白名单
  5. 点击「启动悬浮窗」
  6. 拖拽可移动到任意位置
  7. [自启] 开启「开机自启动」让应用智能判断开机后是否自动恢复

────────────────────────────────────────

版本历史
────────────────────────────────────────

  v1.81  (当前版本)
          修复：悬浮窗横竖屏全屏可达并稳定贴合——FLAG_LAYOUT_IN_SCREEN 使覆盖层含
                物理屏幕状态栏区域，钳位用物理屏幕边界并按四边安全区计算，旋转后二次
                钳位；新增默认位置（左上角距顶 20%）与悬浮窗控制页「一键重置位置」按钮
          优化：家人列表按服务器名册全量同步——服务器移除的成员自动从本机清理，
                不再残留已删除的测试成员
          优化：家人加入审核停用——新成员输入家庭码直接进房，免创建人批准
          优化：ADB 连通即自动授权常开——删除授权开关，环回 5555 通道路径补齐授权，
                电池优化白名单自动加白（dumpsys deviceidle）
          优化：去除家人位置共享独立前台通知——服务改普通后台，后台常驻借同进程
                悬浮窗前台服务保活；悬浮窗通知的功耗与温度合并到同一标题行同字号，
                通知栏不再重复显示「家人位置共享运行中」
          修复：无障碍保活开关响应式同步——监听系统无障碍设置变化，自动授权或手动
                开关后开关即时更新为正确状态，无需退出重进页面

  v1.80
          修复：地图标记系统性偏移数百米——国测局 GCJ-02 算法两处实现错误
                （三角项单位、偏移分母缺 π），模拟器注入与真机伟业双子塔
                实测落点精确；室内 WiFi 定位源固有百米级偏移属正常现象
          修复：悬浮窗位置——旋转屏幕不再污染位置记录，横竖屏各自记忆，
                无对应记录时按屏幕尺寸等比映射回退
          修复：家人加入流程偶发双 WebSocket 连接（僵尸心跳）——start
                收敛为单次调用 + 信令客户端迟到回调防护
          优化：地图信息卡显示附近 POI 地标（如「近XX小区」），不再露出
                裸经纬度，并附精度提示（如「精度 ±15m」）
          修复：逆地理编码偶发无限循环——请求去重 + 距离/冷却双重限流
          修复：后台定位权限被拒时明确 Toast 提示后果，不再静默失败
          加固：APK 下载域白名单（仅自家发布域）、WebView 仅放行 https、
                更新日志脱敏；定位线程池/配对 socket/WakeLock 泄漏修复
          修复：加入家庭查询超时兜底、家庭存储并发写保护、港澳台坐标
                免强行偏移、重连退避竞态等系列逻辑修复

  v1.79
          修复：家人位置偏差 2km——定位拒绝陈旧系统缓存（30 秒新鲜度过滤，
                vivo 等机型毫秒级回吐分钟级旧缓存不再被当作实时位置）
          优化：先粗后精多次回传——粗定位先到先显，GPS/高德更优结果到达自动覆盖
          新增：接入高德定位 SDK 作为定位源——室内 WiFi 指纹实测 10~30 米，
                室外自动融合 GNSS；异常时自动退回系统定位，零回归
          修复：全链路坐标系统一——GPS(WGS-84)/网络与高德(GCJ-02)/百度地图(BD-09)
                混画导致的东西向约 2km 系统性偏移，采集与渲染两端按标准算法转换
          新增：地图交互——点击蓝点/红点放大到街道级并弹信息窗口（名称+详细地址），
                「全景视角」一键恢复；两点与连线强制完整显示不被信息卡遮挡，
                全景视野提供点击放大操作提示
          优化：家人列表精简——「获取位置」按钮移除，进入地图页即自动请求定位
          新增：请求反馈——对方不在线/信令未连接时页面顶部提示（此前静默无响应）
          新增：加入家庭后引导开启「始终允许」后台定位权限，服务被系统拉起后仍可应答
          优化：信令 25 秒应用级心跳，缩短弱网/省电场景半开连接感知时间

  v1.78
          新增：内置特权服务(自包含载体)——一次无线调试连接后，特权能力经本应用
                自带守护进程(libbfd)独立存活，关闭无线调试后高精度数据照常工作，
                无需安装任何第三方应用；连接策略升级为 环回直连→无线调试→基础数据
          新增：特权通道载体可选——「内置常驻服务(默认)」/「Shizuku 服务」双载体自由切换
          新增：连接后自动开启所需权限(可选开关)——WRITE_SECURE_SETTINGS、无障碍保活、
                悬浮窗、定位权限按需自动补齐，每步读回验证
          优化：ADB 断线重连提速——重连退避上限 15 分钟降至 60 秒，亮屏/解锁立即重连；
                ADB 卡片显示连接诊断(最近成功时间/失败原因)
          修复：无线调试服务发现窗口关闭后迟到回调导致进程崩溃(长时间「重连中」的根因)
          修复：ADB 客户端无超时与失败路径 socket 泄漏(握手挂起卡死重连循环)
          兼容：vivo 等 ROM 拒绝固化 adbd 网络端口时自动退化，重开一次无线调试即可恢复

  v1.77
          新增：家人位置共享加入审核——创建家庭前查询家庭码是否被占用，
                新成员加入需创建人批准/拒绝（join-pending/join-request 信令协议），
                批准后断线重连免审核；家庭关系（创建人/批准成员）服务端持久化，重启不丢失
          新增：成员本地备注——自己设备上给对方改备注（不影响对方），列表与地图同步显示
          新增：退出家庭（替代原删除成员）——防止误删家人；加入/更换家庭自动清空旧成员
          优化：家人列表置顶进页即见，家庭码/服务设置沉底；服务开启后自动常驻可被请求
          优化：定位并发请求——网络定位先出位置、GPS/北斗后到自动更新到精确坐标
          修复：家人地图标记点不显示（R8 混淆 keep map 包，Gson 跨 JS 契约字段名）
          修复：地图页系统返回键误退出应用（改 OnBackPressedDispatcher，与返回箭头一致）
          修复：位置时间戳取当前时刻（mock/异常定位源旧时间戳导致显示「x 小时前」错误）

  v1.76
          新增：家人位置共享——两台设备输入同一 6 位家庭码配对，
                按需获取家人位置（loc-req/loc-res 信令中继），
                WebRTC DataChannel 点对点推送（自建信令 + Coturn TURN 兜底），
                百度地图展示家人位置（标记/坐标/精度/上次时间），
                前台服务常驻连接（低调通知），隐私开关可关停位置应答；
                x86 模拟器自动降级信令中继模式（WebRTC native 库不兼容时）
          新增：家人 Tab（首页 | 外观 | 家人 | 关于），Material 3 卡片式界面
          架构：新增 p2p/（信令+WebRTC）、family/（家庭存储）、
                location/（按需定位）、map/（地图抽象+百度实现）分层

  v1.75
          重构：电池数据源接口化（BatteryProvider 抽象），为高精度档接入做准备，
                行为零变化
          新增：内置 ADB 无线调试特权通道（「高精度数据源」开关，默认关闭）——
                无需 Root、无需安装 Shizuku 独立应用；协议栈移植自 Shizuku
                （Apache-2.0）；通知栏直回配对码一次完成配对，之后 NSD 漫游自动重连
          新增：高精度数据生效——battery/power_now 功率直读（幅值+充电定符号）、
                温度直读；逐字段降级，通道断开自动回退基础档，悬浮窗永不断流
          新增：无障碍保活自愈——服务被系统/厂商意外关闭后自动写回
                （pm grant 自授 WRITE_SECURE_SETTINGS 优先，shell 写回兜底，
                退避重试，成功发 4 秒自动消失通知；应用内主动关闭永不触发）
          新增：配对一次、后续免重复配对——开关关→开直接重连不再弹配对引导；
                无线调试关闭再开启后自动恢复；设备撤销信任（TLS 握手失败）时
                自动暂停重连并给出「重新配对」入口
          优化：通知构建统一收口（悬浮窗前台/功耗刷新/自愈/ADB 配对共用工厂）
          修复：无障碍自愈成功通知误用相对时长导致看不到（平台 timeoutAfter
                为绝对时间戳，已修正）；自愈代码编译错误（退避重试引用未定义变量）

  v1.74
          新增：无障碍保活（GKD 同款机制）——系统级绑定大幅提升后台存活率，
                重启后悬浮窗免开机广播自动恢复，不受厂商「自启动管理」拦截；
                首页新增「无障碍保活」开关（仅用于保活，不读取屏幕内容）
          优化：无障碍保活在位时自动停用 15 分钟心跳闹钟与看门狗周期任务
                （零周期唤醒省电），关闭无障碍后周期兜底通道自动恢复
          修复：实时通知功耗无文字标签；符号格式与悬浮窗统一（正=充电/负=放电）

  v1.73
          修复：Android 13+ 新装用户看不到任何通知——新增 POST_NOTIFICATIONS 运行时权限请求
                （首次启动请求一次，拒绝后 Toast 提示去系统设置开启）
          修复：权限弹窗盖住应用时触发「隐藏后台」逻辑导致应用自我关闭，
                请求前标记外部跳转予以豁免
          修复：BatteryMonitor 更新的实时通知补点击跳转（此前更新后点通知无响应）
          优化：通知刷新阈值 0.1→0.5，避免充电时通知约每 2 秒重发
          优化：用户手动关闭悬浮窗后取消 JobScheduler 看门狗，避免周期任务空转
          安全：签名口令迁移至本地 keystore.properties 并轮换密钥库口令（签名不变，升级不受影响）
          升级：core-ktx 1.18 / lifecycle 2.10 / activity-compose 1.13 / 协程 1.11，
                compileSdk 35→36（Target SDK 保持 34）

  v1.72
          回退：悬浮窗默认配色恢复为深灰背景 #666666 + 白色文字
                （外观页默认值回退，新增浅蓝/天蓝/蓝色预设色保留，老用户数据不动）
          修复：格式化统一 Locale.US，避免非英文地区小数点异常
          修复：BatteryMonitor 协程作用域 stop 后再次 start 静默失效
          修复：悬浮窗权限缺失时 FLOATING_WAS_RUNNING 残留导致开机自启空转
          修复：onTaskRemoved 在服务仍存活时无谓重启
          保活：心跳间隔 5 分钟放宽到 15 分钟，降低锁屏功耗
          保活：新增电池优化白名单状态提示 + 国产 ROM 自启动一键跳转
          保活：新增 JobScheduler 15 分钟兜底看门狗（第二通道）
          版本号升级

  v1.71
          样式：应用界面改为天蓝浅色系，关闭动态取色，默认浅色主题
          主色 #1E88E5 天蓝，背景 #F4F9FF 浅蓝白，卡片 #FFFFFF
          统一 8dp 网格间距，卡片圆角保持 16dp，标题字体 24sp
          首页/外观/关于按钮改为天蓝主按钮
          悬浮窗默认配色改为浅蓝背景 #B3E5FC + 深蓝文字 #0D47A1
          外观页新增浅蓝/天蓝/蓝色预设，旧默认配色自动迁移
          版本号升级

  v1.70
          样式：应用界面改为暖色浅色系，关闭动态取色，默认浅色主题
          主色 #F57C00 暖橙，背景 #FFF7F0 奶油白，卡片 #FFFCF9
          统一 8dp 网格间距，卡片圆角保持 16dp，标题字体 24sp
          首页/外观/关于按钮改为高亮暖橙主按钮
          悬浮窗默认配色与功能默认值保持不变
          版本号升级

  v1.69
          修复：BatteryMonitor 功耗缓存 NaN 污染导致通知不更新
                lastNotifiedPower 初值由 NaN 改为 -Infinity，避免 abs(watts-NaN) 恒为 false；
                功耗为 NaN 时不参与比较、不回写缓存，修复功耗从不可用转为有效时通知不触发
          修复：onTaskRemoved 保活闹钟升级为 setExactAndAllowWhileIdle
                与心跳保活一致突破 Doze，确保划掉应用后准时重启服务；
                补 SecurityException 降级为 set，权限缺失时不崩溃
          优化：前台通知文本资源化到 strings.xml
                buildForegroundNotification 标题/正文改用 getString(R.string.*)，
                符合字符串集中管理规范
          修复：下载无 Content-Length 时进度卡 0%
                服务器未返回 Content-Length（chunked 编码）时按已下载字节滚动进度
                （每 50KB 进 1%，封顶 99%），完成由 Completed 状态接管
          版本号升级

          操作日志（2026-08-15 会话）
          修复：BatteryMonitor 功耗缓存 NaN 污染（lastNotifiedPower 初值 NaN→-Infinity，Nan 不参与比较/不回写）
          修复：onTaskRemoved 保活闹钟 set→setExactAndAllowWhileIdle + SecurityException 降级
          修复：通知文本资源化到 strings.xml
          修复：下载无 Content-Length 时滚动进度（每 50KB 进 1%，封顶 99%）
          新增：.gitignore（构建产物/敏感文件/CLAUDE.md）
          发布：GitHub + Gitee force push 清理含 APK 历史，v1.69 Release 发布并上传 APK 附件
          versionCode 31→33

  v1.68
          功耗显示样式与温度显示完全统一
          移除功耗文本充放电动态颜色逻辑（充电绿/放电橙），改为与温度文本一致的用户自定义颜色
          updatePower() 移除 isCharging 参数，颜色由 applyAppearance() 统一管理
          BatteryMonitor updateDisplay/fetchBatteryData 同步简化，移除冗余 charging 变量
          功耗仍保留带符号格式（%+.1fW），通过正负值区分充放电
          修复：UpdateChecker User-Agent 改用 BuildConfig.VERSION_NAME，避免硬编码版本号
          修复：UpdateChecker 添加 HTTP 响应码检查，非 2xx 状态码直接返回 null
          修复：UpdateChecker 版本比较逻辑改为按点分段比较，解决 1.6 vs 1.60 比较错误
          修复：FloatingWindowView coerceIn 崩溃保护，maxX/maxY 使用 maxOf 确保最小值
          修复：FloatingWindowView 自定义字段 layoutParams 重命名为 windowParams，避免遮蔽父类属性
          修复：MainActivity isLaunchingExternal 状态修正，移到实际 startActivity 调用前
          修复：AppearanceScreen 统一使用 PrefsKeys 常量替换硬编码的 SharedPreferences key
          版本号升级
          优化：移除 minSdk=34 下冗余的 SDK 版本检查（M/LOLLIPOP/O/S 条件永真，保留内部代码）
          优化：MainActivity/AboutScreen 魔法数字 34 替换为 Build.VERSION_CODES.UPSIDE_DOWN_CAKE
          优化：FloatingWindowService 移除 TYPE_PHONE 不可达分支，移除手动通知 flags 操作依赖 setOngoing(true)
          优化：FloatingWindowView/BootReceiver/HomeScreen/Theme 移除冗余 SDK 版本检查
          同步知识图谱：v1.68更新实体补充 P1+P2+P3 修复信息，更新 UpdateChecker/FloatingWindowView/MainActivity/FloatingWindowService/BootReceiver/HomeScreen/AboutScreen/AppearanceScreen/ui-theme模块 等实体及关系
          
  v1.67
          全量代码质量审计与修复
          修复：FloatingWindowService.showFloatingWindow() 添加 addView try-catch 异常保护（P0）
          修复：MainActivity 权限引导 showGuide() lambda 异常保护（P1）
          修复：AboutScreen RestrictedSettingsDialog 打开应用信息页异常保护（P1）
          修复：ApkDownloader.isValidApkFile() 读取返回值校验（P2）
          修复：BatteryMonitor.lastNotifiedPower 初始值改为 Float.NaN
          修复：FloatingWindowView 添加 performClick() 无障碍兼容
          修复：拖拽坐标回退 rawX/rawY，解决 getX(0) 导致的视图移动后反馈循环问题
          修复：功耗公式改用电池状态决定符号，解决制造商 currentNow 符号不一致问题
          修复：功耗字体大小与温度显示保持一致
          版本号升级
          
  v1.66
          功耗显示增加充放电感知：充电显示正值（绿色），放电显示负值（橙色）
          悬浮窗功耗文本美学设计：充电绿色 #4CAF50、放电橙色 #FF9800
          首页功耗开关增加符号说明（充电正值、耗电负值）
          代码质量提升：BatteryMonitor 线程安全改用 AtomicBoolean
          代码质量提升：FloatingWindowService mainHandler 增加空值防护
          AboutScreen RestrictedSettingsDialog onOpenAppInfo 回调添加 try-catch 异常保护
          版本号升级
          
  v1.65
          移除 Shizuku 依赖及相关代码
          温度读取改为仅使用 Android 标准 BatteryManager API
          版本号升级
          修复 BootReceiver 开机自启动未校验悬浮窗权限导致"幽灵服务"问题
          补充 Android 14 specialUse FGS subtype 合规声明
          心跳闹钟升级为 setExactAndAllowWhileIdle 增强 Doze 模式下保活
          优化 onTaskRemoved 重启异常处理兼容 Android 12+
          1x1 保活覆盖层增加失败重试机制
          开机自启动增加短暂 WakeLock 确保服务拉起

  v1.64
          全量代码审查与 Bug 修复
          修复：清理旧组件残留引用
          修复：BootReceiver 开机自启延迟检查广播未注册自定义 action
          修复：锁定状态缓存不同步（关闭锁定开关后悬浮窗仍无法拖拽）
          修复：WebViewActivity 内置加载逻辑修正
          版本号升级

  v1.63
          UI 全面重新设计：采用 Material Design 3 设计语言
          统一设计规范：8dp 网格系统、16dp 卡片圆角、24sp 标题字体
          新增 DesignSystem 设计规范文件：集中管理间距、圆角、字体等变量
          重构通用组件库：SettingSwitchCard、SliderSettingCard、ColorPickerSection
          首页优化：状态指示灯、动画按钮、分组标题
          外观页优化：主题模式三段式选择、颜色选择器、滑块控件
          关于页优化：权限引导卡片、开机自启设置、版本更新检查、关于信息
          底部导航优化：选中状态、动画切换、触觉反馈
          代码质量提升：函数级注释、组件化设计、状态管理优化
          iOS 风格全局扩展：所有卡片添加彩色圆形图标背景
          首页功能卡片：功耗显示(蓝色)、锁定悬浮窗(紫色)、隐藏后台(橙色)
          关于页卡片：权限引导(绿色)、开机自启(蓝色)、版本更新(橙色)、关于信息(紫色)
          外观页卡片：主题模式(靛蓝)、字体大小(青色)、圆角曲率(棕色)、背景颜色(粉色)、文字颜色(深灰)、透明度(蓝灰)
          卡片背景统一改为不透明的 surfaceContainerLow，增强视觉层次感

  v1.62
          全量代码审查与质量优化
          修复版本比较算法（逐段整数比较，支持多段版本号如 1.62.1）
          修复 FloatingWindowService 生命周期：isRunning 赋值顺序修正、onDestroy 写回运行状态
          修复 MainActivity 异常处理：更新检查/权限设置/电池优化全部包裹 try-catch
          WebViewActivity 内置浏览：链接均在应用内 WebView 加载
          提取 SharedPreferences key 为 PrefsKeys 常量对象，消除所有硬编码字符串
          优化 SharedPreferences 监听粒度：仅监听外观相关 key 变化才刷新悬浮窗
          Theme 动态取色默认关闭，自定义天蓝配色在 Android 12+ 上生效
          AnimatedToggleButton 添加点击缩放动画反馈
          UpdateChecker User-Agent 动态获取版本号

  v1.61
          Android 14+ 受限设置引导：首次请求悬浮窗权限弹出分步引导对话框
          关于页新增「解除权限限制（Android 14+）」按钮，可随时查看引导

  v1.60
          移除无障碍保活服务
          启动自动更新弹窗：打开 App 立即检测并提示更新
          进程优先级最大化：IMPORTANCE_LOW + REDELIVER_INTENT + getForegroundService
          通知栏常驻小图标 + stopWithTask=false 双重防杀
          清理所有无障碍相关代码、权限和配置残留
          修复 3 个 Kotlin 编译 warning（弃用 API + 类型不匹配）
          版本号改用 BuildConfig.VERSION_NAME 自动同步
          全量代码审计修复：Shizuku 资源泄漏 try-finally 保护、BatteryMonitor @Volatile 线程可见性、FloatingWindowService 空安全优化

  v1.59
           修复保活闪退(非阻塞设计+防Activity销毁)
           保活开关状态与实际运行一致; 隐藏"正在其他应用上层"通知(IMPORTANCE_MIN)
           横竖屏切换位置偏差修复; 代码质量优化(内存泄漏/资源泄漏/弃用API)

  v1.58
           版本号升级、外观默认值调整、隐藏后台默认开启、首次启动权限引导、WebView内置浏览、双源更新检测

  v1.57  2026-06-18
          UI 全面重构: 多页面底部导航架构(首页/外观/关于)
          主题色系改为天蓝主调(#1A73E8); 零阴影扁平卡片设计
          全 Material Icons 替换 Emoji; 功耗开关移至首页
          修复 GitHub/Gitee 链接闪退(Intent.ACTION_VIEW跳转浏览器)
          SettingSwitchCard icon 参数改为 Composable 类型

  v1.56  2026-06-17
         应用名称改为「神奇悬浮窗」; UI/UX 全面 Material Design 优化
         主题色系重构; 卡片分组区划; 交互动画增强
         新增关于页面; 悬浮窗拖拽震动反馈

  v1.55  2026-06-17
         全量代码审查与优化: MainScreen重构(拆分为10+子组件)
         ShizukuHelper线程安全修复(synchronized双重检查锁定)
         BatteryMonitor IntentFilter缓存优化
         FloatingWindowView density缓存+Math→kotlin.math.abs

  v1.54  2026-06-17
         保活开关优化: ADB/Shizuku 优先静默启用，无 ADB 时引导系统无障碍设置
         开关状态真实同步; APK 下载 User-Agent 修复

  v1.53  2026-06-16
         全新保活体系: 借鉴 GKD 保活机制，新增无损保活遮蔽层 + 可选无障碍保活
         进程保活/开机自启独立开关控制; 智能开机自启判断
         UI 新增保活/自启状态卡片

  v1.52  2026-06-16
         构建改为 release 正式版(APK 1.6MB，APK 签名)
         修复「开发者证书」; App 内可视化下载更新

  v1.51  2026-06-16
         性能优化: Shizuku 反射缓存; 协程调度优化; Intent 注册合并
         锁定状态/视图尺寸内存缓存; 通知阈值去重

  v1.5   2026-06-16
         修复双击锁定(开关与状态分离); 柔光蓝锁定边框
         手动检查更新按钮; 版本升级

  v1.43  2026-06-15
         修复自动更新 API; 修复双击锁定开关控制
         悬浮窗状态实时记录; 版本升级

  v1.42  2026-06-15
         双击锁定悬浮窗; UI 全面美化; 代码清理优化; 版本升级

  v1.41  2026-06-15
         取消悬浮窗点击; 自动版本更新检测
         悬浮窗开关移至顶部; UI 优化; 版本升级

  v1.4   2026-06-15
         SDK 降级至兼容安卓 14; Home/返回双键隐藏后台; 版本号升级

  v1.33  2026-06-15
         横->竖切换吸附; 返回键隐藏后台; UI 更新

  v1.32  2026-06-15
         修复横屏拖拽回弹; 完善隐藏后台

  v1.31  2026-06-15
         优化拖拽边界; 隐藏后台; APK 改名

  v1.3   2026-06-15
         修复图标; 横竖屏边界优化; 代码清理

  v1.2   2026-06-15
         横屏贴左边缘; 设置实时更新; 功耗修复

  v1.1   2026-06-14
         项目重构: 双行视图; 资源精简; APK 缩小 82%

────────────────────────────────────────

作者: qinzuoyong

Gitee: https://gitee.com/qinzuoyong/floating-data

GitHub: https://github.com/qinzuoyong/floating-data

────────────────────────────────────────