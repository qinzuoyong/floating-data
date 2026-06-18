# 手机信息悬浮窗

> 实时监测电池温度与功耗的 Android 悬浮窗工具
> 版本: 1.57 | 最低支持: Android 14 (API 34)

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
         多级保活体系: 前台 Service(常驻通知) + 1px 不可见 Overlay + 可选的无障碍服务保活

  [更新] 自动更新检测
         打开应用自动检测是否有新版本，可选升级或忽略

  [下载] App 内可视化下载
         点击「立即升级」在 App 内下载 APK，实时显示 0%~100% 圆形进度 + 百分比动画

  [锁定] 双击锁定
         双击悬浮窗可锁定/解锁拖拽位置，独立开关控制

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
  6. [保活] 可选: 连接 Shizuku 后开启「进程保活」获取最强保活效果
  7. [自启] 开启「开机自启动」让应用智能判断开机后是否自动恢复

────────────────────────────────────────

版本历史
────────────────────────────────────────

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

代码优化记录 (2026-06-17)
────────────────────────────────────────

本次全量代码审查与优化（第二轮）修复了以下问题：

1. MainScreen 重构（Clean Architecture）
   - 将 600+ 行的 MainScreen 拆分为 10+ 独立子组件
   - 新增 SettingSwitchCard、ColorPickerSection、SliderSettingCard 公共组件
   - 每个组件职责单一，可读性和可维护性大幅提升

2. ShizukuHelper 线程安全（Critical）
   - 反射缓存添加 synchronized 双重检查锁定模式
   - cacheProcessMethods() 单独提取为同步方法
   - 消除多线程并发时的 NullPointerException 风险

3. BatteryMonitor 性能优化
   - 缓存 IntentFilter 对象，消除每 2 秒 new IntentFilter() 的内存开销
   - 统一使用 kotlin.math.abs 替代 Math.abs

4. FloatingWindowView 性能优化
   - density 字段在 init 时缓存，避免每次 dpToPx 调用都 getDisplayMetrics
   - 使用 DRAG_THRESHOLD 常量化拖拽阈值
   - 统一使用 kotlin.math.abs 替代 Math.abs（减少 JNI 调用）

5. 版本号升级
   - versionCode 17→18, versionName 1.54→1.55

6. v1.56 应用名与UI优化
   - 应用名: 勇哥 → 神奇悬浮窗
   - 主题色系: 紫→青蓝主调; 新增暖橙辅助色
   - 卡片分组: 分区段头部, 信息层级清晰
   - 动画: 淡入+按钮缩放+锁定震动
   - 新增 AboutCard 关于页面

6. 构建验证（第二轮）
   - assembleDebug: ✅ 通过
   - test: ✅ 通过
   - lint: ✅ 通过（0 error, 52 warnings）
   - assembleRelease: ✅ 通过
