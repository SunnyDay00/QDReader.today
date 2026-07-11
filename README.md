# 起点自动签到

起点自动签到是一个 Android APK 项目，目标是通过 Android 无障碍服务、OCR 识别和自动化点击，实现对起点读书 App 的自动签到。

目标应用：

- 应用名称：起点读书
- Android 包名：`com.qidian.QDReader`

## v0.1 框架能力

当前版本实现自动化框架和一段起点福利任务流程：

- Compose 工具型管理界面，应用显示名称为“起点自动签到”，界面为浅灰背景 + 白色紧凑工具卡片，底部显示当前 APK 版本号，启动器图标为红底白色书本 adaptive icon。
- 无障碍服务状态检测。
- 通知权限状态检测。
- 起点读书安装检测和打开入口。
- 无障碍截图、坐标点击、滑动、返回等桥接接口。
- bundled ML Kit 中文 OCR 离线识别能力。
- OpenCV 模板匹配接口。
- 右上角广告关闭按钮模板匹配，模板放在 `app/src/main/assets/templates/ad_close/`。
- 自动化控制器、屏幕分析器、动作执行器和签到流程接口。
- 起点流程：重启起点、识别底部 tab、进入“我”、判断未登录、等待 2 秒后点击“福利中心”；福利中心内通过截图 OCR 分组冗余验证页面、仅上滑一次，并处理广告奖励任务入口。
- 关键步骤带局部重试：`我`、`福利中心`、广告浏览弹窗打开最多重试 3 次；任务 `去完成` 在安全窗口内持续重试。
- 福利任务按钮支持 OCR 行兜底：标题能识别但右侧按钮没配对时，会按同一行右侧按钮列重新定位。
- 广告奖励确认弹窗优先识别点击 `知道了 / 我知道了 / 知道啦 / 知道`，并保留中心弹窗奖励文案和任务列表遮挡恢复的坐标兜底。
- 福利中心任务已领取时自动跳过；任务仍是 `去完成` 时会在安全窗口内持续点击，超过窗口仍未进入广告页则按步骤卡住触发整轮重启。
- 单轮广告从点击 `去完成` 开始 60 秒内必须完成奖励确认处理，否则按卡住触发整轮重启。
- 整次自动化流程带 15 分钟看门狗，防止内部 OCR 等待或页面状态异常导致无限卡住。
- 每日签到时间、失败重启次数设置和自动任务入口。

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

## 版本号

APK 版本号来自 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`，主界面底部会显示 `版本 vX.Y.Z (code)`。

当前仓库配置了 `.githooks/pre-commit`，每次本地提交前会自动递增 `versionCode` 和 `versionName` 的 patch 位，并把版本文件加入本次提交。

## 本地隐私

本仓库忽略以下本地文件和目录：

- `AGENTS.md`：本地 AI agent 说明和设备连接信息。
- `脚本/`：本地辅助脚本、临时工具或调试文件。
- `local.properties`：本地 Android SDK 配置。
