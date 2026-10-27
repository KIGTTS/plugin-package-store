# 插件商店

KIGTTS的插件商店独立仓库。本仓库采用 **MIT**，完整文本见[LICENSE](LICENSE)，来源与第三方许可见[NOTICE.md](NOTICE.md)。SDK的许可不会覆盖插件的许可。

## 0.16.3

修复部分新版仍显示“安装”或旧下载状态的问题。列表、详情页与下载管理统一显示更新状态，下载完成后可直接更新；历史版本和资源添加保留各自的操作。

## 0.16.2

支持从商店获取字体，添加后可在外观设置中选择。字体采用独立资源包，不进入插件管理器。

## 0.16.1

资源添加失败后可以重试，短暂连接中断后继续读取进度。配合新版 KIGTTS，可在后台添加资源；商店首页与详情采用设置页面同款分组卡片。

## 0.16.0

资源分区增加名片边框和参数字贴。分享链接可以直接打开详情，先添加所需内容，再选择是否订阅来源。

## 0.13.0

首页保留分类 Tab 和完整插件列表，顶部可进入分类或来源分区。往下滚动会继续加载插件，已浏览的条目保留在列表中。平板上插件分成两列，顶部入口排成一行；来源支持 Material Symbol、位图或 SVG 图标。

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
