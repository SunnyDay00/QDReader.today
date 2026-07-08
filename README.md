# QDReader.today

QDReader.today 是一个 Android APK 项目，目标是通过 Android 无障碍服务、OCR 识别和自动化点击，实现对起点读书 App 的自动签到。

目标应用：

- 应用名称：起点读书
- Android 包名：`com.qidian.QDReader`

## v0.1 框架能力

当前版本实现自动化框架和一段起点福利任务流程：

- Compose 工具型管理界面。
- 无障碍服务状态检测和授权入口。
- 通知权限申请和状态检测。
- 起点读书安装检测和打开入口。
- 无障碍截图、坐标点击、滑动、返回等桥接接口。
- bundled ML Kit 中文 OCR 离线识别测试。
- OpenCV 模板匹配接口和测试入口。
- 右上角广告关闭按钮模板匹配，模板放在 `app/src/main/assets/templates/ad_close/`。
- 自动化控制器、屏幕分析器、动作执行器和签到流程接口。
- 起点流程：重启起点、识别底部 tab、进入“我”、判断未登录、等待 2 秒后点击“福利中心”；福利中心内通过截图 OCR 验证页面、仅上滑一次，并处理广告奖励任务入口。
- 关键步骤带局部重试：`我`、`福利中心`、任务 `去完成`、广告浏览弹窗打开最多重试 3 次，避免单次点击或识别失败中断整体流程。
- 福利中心三项广告奖励任务独立执行：已领取的任务自动跳过，单项失败会记录日志并继续下一项。
- 每日签到时间设置和调度入口。

## 技术文档

完整技术细节见 `docs/TECHNICAL.md`，包括架构、关键接口、当前自动化流程、OCR/OpenCV 策略、构建约束、失败诊断和扩展指南。

后续修改项目结构、依赖、自动化步骤、OCR 锚点、模板匹配、构建流程或隐私规则时，必须同步更新 `docs/TECHNICAL.md` 和 README 摘要。

## 构建方式

APK 不在本地构建。Debug APK 由 GitHub Actions 构建并上传为 artifact：

- Workflow：`.github/workflows/android-debug-apk.yml`
- 触发方式：`push` 或手动 `workflow_dispatch`
- 构建任务：`./gradlew assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

本地只做代码检查、敏感信息扫描和 workflow 检查，不执行本地 APK 构建命令。

## 本地隐私

本仓库忽略以下本地文件和目录：

- `AGENTS.md`：本地 AI agent 说明和设备连接信息。
- `脚本/`：本地辅助脚本、临时工具或调试文件。
- `local.properties`：本地 Android SDK 配置。
