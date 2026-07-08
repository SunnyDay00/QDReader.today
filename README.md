# QDReader.today

QDReader.today 是一个 Android APK 项目，目标是通过 Android 无障碍服务、OCR 识别和自动化点击，实现对起点读书 App 的自动签到。

目标应用：

- 应用名称：起点读书
- Android 包名：`com.qidian.QDReader`

## v0.1 框架能力

当前版本实现自动化框架和一段起点流程，不包含具体签到领取点击步骤：

- Compose 工具型管理界面。
- 无障碍服务状态检测和授权入口。
- 通知权限申请和状态检测。
- 起点读书安装检测和打开入口。
- 无障碍截图、坐标点击、滑动、返回等桥接接口。
- bundled ML Kit 中文 OCR 离线识别测试。
- OpenCV 模板匹配接口和测试入口。
- 自动化控制器、屏幕分析器、动作执行器和签到流程接口。
- 起点流程：重启起点、识别底部 tab、进入“我”、判断未登录、点击“福利中心”、验证福利中心页面。
- 每日签到时间设置和调度入口。

## 构建方式

APK 不在本地构建。Debug APK 由 GitHub Actions 构建并上传为 artifact：

- Workflow：`.github/workflows/android-debug-apk.yml`
- 触发方式：`push` 或手动 `workflow_dispatch`
- 构建任务：`./gradlew assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 本地隐私

本仓库忽略以下本地文件和目录：

- `AGENTS.md`：本地 AI agent 说明和设备连接信息。
- `脚本/`：本地辅助脚本、临时工具或调试文件。
- `local.properties`：本地 Android SDK 配置。
