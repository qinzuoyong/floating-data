# 手机信息悬浮窗

> 实时监测电池温度与功耗的 Android 悬浮窗工具
> 版本: 1.64 | 最低支持: Android 14 (API 34)

────────────────────────────────────────

功能特性
────────────────────────────────────────

  [温度] 实时温度显示
         悬浮窗显示当前电池温度(℃)，支持 Shizuku sysfs 读取 + BatteryManager 降级

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
       前台 Service(常驻通知) + 1x1 不可见 Overlay 提升进程优先级，防一键清理杀死

  [更新] 自动更新检测
         打开应用自动检测是否有新版本，可选升级或忽略

  [下载] App 内可视化下载
         点击「立即升级」在 App 内下载 APK，实时显示 0%~100% 圆形进度 + 百分比动画

  [锁定] 双击锁定
         双击悬浮窗可锁定/解锁拖拽位置，独立开关控制

  [主题] 主题外观选择
         支持跟随系统/浅色/深色三种模式，实时切换，Material Design 3 设计语言

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
  - Android SDK 35+

构建命令:
  ./gradlew.bat assembleRelease --no-configuration-cache

APK 输出路径:
  _build/app/outputs/apk/release/yongge.apk

技术栈:
  Kotlin         2.2.10
  AGP            9.2.1
  Compile SDK    35
  Min SDK        34 (Android 14)
  Target SDK     34
  Compose BOM    2026.02.01
  Material3      Yes
  Shizuku        13.1.5
  协程           1.7.3

────────────────────────────────────────

使用说明
────────────────────────────────────────

  1. 安装 APK 后打开应用
  2. 开启悬浮窗权限
  3. 忽略电池优化
  4. 点击「启动悬浮窗」
  5. 拖拽可移动到任意位置
  6. [自启] 开启「开机自启动」让应用智能判断开机后是否自动恢复

────────────────────────────────────────

版本历史
────────────────────────────────────────

  v1.64  (当前版本)
          全量代码审查与 Bug 修复
          修复：删除 3 个旧组件残留文件（SettingSwitchCard/SliderSettingCard/ColorPickerSection）
          修复：BootReceiver 开机自启延迟检查广播未注册自定义 action
          修复：锁定状态缓存不同步（关闭锁定开关后悬浮窗仍无法拖拽）
          修复：WebViewActivity URL 白名单加载逻辑修正
          版本号升级

  v1.63
          UI 全面重新设计：采用 Material Design 3 设计语言
          统一设计规范：8dp 网格系统、16dp 卡片圆角、24sp 标题字体
          新增 DesignSystem 设计规范文件：集中管理间距、圆角、字体等变量
          重构通用组件库：SettingCard、SettingSwitchCard、SliderSettingCard、ColorPickerSection
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
          WebViewActivity URL 白名单机制：仅 gitee.com/github.com 在 WebView 内加载，外部链接跳转系统浏览器
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
