# 插件商店

KIGTTS的插件商店独立仓库。本仓库采用 **MIT**，完整文本见[LICENSE](LICENSE)，来源与第三方许可见[NOTICE.md](NOTICE.md)。SDK的许可不会覆盖插件的许可。

## 0.12.0

按分类或来源进入分区，每页浏览12款插件。各分区保留独立页码，刷新后自动适应目录变化；来源可显示Material Symbol、位图或SVG图标。

## 0.11.1

插件列表与详情页都能直接下载，并在原位查看进度。搜索进入独立页面，输入即可查找，返回退出搜索。下载管理可继续安装、取消或重试。

## 0.10.3

安装前先列出本次需要的插件与依赖，确认后再开始下载。安装信息采用统一列表布局，插件包可直接展示自己声明的图标。

## 构建

需要JDK17、Android SDK36、PowerShell7。克隆时获取已固定版本的SDK及共享组件，不需要克隆主程序。

```powershell
git clone --recurse-submodules https://github.com/KIGTTS/plugin-package-store.git
cd plugin-package-store
./gradlew.bat testDebugUnitTest assembleDebug
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

Linux/macOS使用`./gradlew`。编译产物在`build/outputs/aar`，签名插件包在`build/plugin-packages`。发布私钥始终由维护者保管，不提交到本仓库；正式更新沿用既有发布者签名、插件ID与Appdata范围。已发布版本不可覆盖，发行新内容须增加版本号。

[SDK与接口](https://github.com/KIGTTS/plugin-sdk) · [官方源](https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Official-Plugins)
