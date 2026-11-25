# AList 在线更新发布指南

当前线上版本为 `0.3.20-login-fixes`，`versionCode 27`。登录页可点击“检查更新”，曲库页从齿轮设置进入“检查更新”；登录页、曲库页、播放页和设置页恢复前台时按 12 小时间隔自动联网检查。更新不依赖用户登录飞牛 NAS。仅在本地生成更新包不会改变线上版本。

发现可用新版时，弹窗展示当前版本、新版本及清单中的多行“更新内容”，可选择“稍后”或“下载并安装”。成功检查的版本信息保存在本机，检查期间切换页面或退到后台不会丢失提醒。选择“稍后”后同一新版在本次应用进程运行期间不重复自动提醒，重新启动应用后仍会提醒未安装的新版；手动检查随时可以重新提示。检查失败不记入 12 小时间隔，已有缓存时自动检查失败仍能提示此前发现的新版。

已配置的逻辑更新地址：

```text
http://ikuai.qiuxiaoyu.top:5244/d/20T%E5%A4%A7%E7%A1%AC%E7%9B%98/QY_net/feiniu-updates/update.json
```

客户端先通过 AList `/api/fs/get` 访客接口获取每个文件的下载签名，再访问该文件；清单与 APK 分别获取签名。无需在安装包中保存 AList 用户名、密码或管理员令牌，也不必关闭全站下载签名。

## 需要运行的服务

当前公网 AList 的 HTTP 服务已经可访问，保持该服务及公网端口 `5244` 可用即可。更新只使用 AList 的文件查询和下载，不需要额外的更新后端。

`5244` 当前不是 HTTPS 端口，不要把地址前缀直接改成 `https://`。如以后使用 HTTPS，可复用已有的 Nginx/Caddy 反向代理，以有效域名证书提供公网 HTTPS，再转发到 AList 的 HTTP 端口。客户端支持 HTTP 转 HTTPS 的下载跳转，HTTPS 连接仍校验证书和主机名。

## AList 一次性设置

1. 当前发布目录为 `/20T大硬盘/QY_net/feiniu-updates`，本轮访客查询确认文件由 `Local` 本机存储提供。
2. `guest` 访客已启用，本轮实测可以读取更新清单和下载 APK。保持只读、下载权限即可。
3. 访客的基本路径使用 `/20T大硬盘/QY_net/feiniu-updates`，让访客只读取这个发布目录；不要赋予访客上传、删除或管理权限。客户端也兼容访客基本路径为 `/` 的站点。
4. 更新目录不要设目录密码或要求登录。如果父目录的元信息限制会继承到这里，应给更新目录设置明确的公开读取规则。
5. 保留现有“签名所有”设置。客户端会自动取得按文件生成的签名，不需要把管理员令牌或手工复制的过期签名写进 APK。
6. 在目录里上传下面生成的 APK 和 `update.json`。AList 网站地址若需要设置，应使用车机能访问的公网地址，而不是 `localhost` 或内网地址。

本机存储和下载签名设置的官方资料：

- [AList 本机存储](https://alistgo.com/zh/guide/drivers/local.html)
- [AList 全局签名设置](https://alistgo.com/zh/config/global.html)
- [AList 文件查询接口](https://alistgo.com/guide/api/fs.html)

## 首次让现有用户接入

以前分发的 0.3.6 安装包更新地址为空，0.3.7 的默认路径是旧的单层目录。因此这些用户需要先手动安装一次本版 APK，后续即可通过当前新目录在应用内升级。

本次构建沿用当前调试签名，并使用发布目录中上一版 `0.3.19-playback-fixes / versionCode 26` 的实际 APK 核对；旧包摘要与线上清单一致。对已经分发给用户的其它 APK，应使用那份实际旧包核对；同包名、同签名且更高的 `versionCode` 才能覆盖安装。不要在已有用户开始更新后临时更换签名密钥。

## 每次发布

默认交付规则（用户于 2026-10-17 确认）：以后要求打包时，生成可升级的更新 APK，并同时形成与该 APK 对应的 `update.json`。除非明确要求测试包，单独的测试安装包不作为最终交付。每次打包都按下方流程递增版本、沿用签名，并校验最终 APK 与清单一致。

1. 只修改 `app/build.gradle` 的 `versionCode` 和 `versionName`；`versionCode` 必须递增。后备构建脚本会读取这份版本信息，不用再修改两处。
2. 使用与已安装旧版相同的密钥构建 APK。当前开发构建命令：

   ```powershell
   .\gradlew.bat assembleDebug
   ```

   如使用既有正式签名，可通过 `tools/manual-build.ps1` 的 `Keystore`、`KeyAlias` 等参数继续使用原密钥。新用户的正式发布密钥应永久保存并备份；不要将密钥和密码上传到网盘。

3. 自动准备上传文件：

   ```powershell
   .\tools\prepare-update.ps1 `
       -Apk .\app\build\outputs\apk\debug\app-debug.apk `
       -PreviousApk .\release\update-upload\FeiniuCarMusic-0.3.19-playback-fixes-26.apk `
       -ChangelogFile .\release\CHANGELOG-0.3.20.txt
   ```

   脚本会从 APK 读取真实包名、版本和最低 Android 版本，验证 APK 签名及新旧包一致性，计算 SHA-256，然后生成：

   ```text
   release/update-upload/
     FeiniuCarMusic-0.3.20-login-fixes-27.apk
     update.json
   ```

   可以用 `-ChangelogFile "更新说明.txt"` 读取多行 UTF-8 说明，或用 `-OutputDirectory` 指定另一个输出目录。相同版本文件名已存在不同 APK 时脚本会拒绝覆盖；每次对外发布都应递增版本。

4. 将生成的 APK 上传到 AList `/20T大硬盘/QY_net/feiniu-updates`，确认上传完成后，最后替换同目录的 `update.json`。保留不同版本的 APK 文件名，避免旧清单引用的包被替换。
5. 用无痕浏览器以访客打开发布目录，确认可以下载文件，再在应用里点击“检查更新”。直接访问未带签名的 `/d/` 地址可能返回签名错误，这是 AList 下载保护；客户端会先取签名。

## 更新清单

```json
{
  "versionCode": 27,
  "versionName": "0.3.20-login-fixes",
  "apkUrl": "FeiniuCarMusic-0.3.20-login-fixes-27.apk",
  "sha256": "E1AE10AB4DC814B413E6B22C75C3D643FD459311B989CF2CAD1898CB5869BD5E",
  "minSdk": 14,
  "changelog": "1. FN Connect 目录服务临时出错时会尝试备用入口，登录失败时会显示内网、中继和外网线路各自的原因。\n2. 改善 Android 5/6 的 HTTPS 证书兼容性。车机时间异常、证书链不受信任、域名解析失败、端口超时及账号验证被拒绝时，会分别给出提示。\n3. 支持粘贴飞牛音乐网页地址。只填域名时按需尝试 HTTPS 443，确认音乐服务后再提交登录。\n4. 支持 NAS 返回 AAC 音频。旧车机无法直接打开完整音频文件时，会通过本机 HTTP 重试同一份文件。\n5. 歌词按账号缓存，首次成功加载后可离线读取。\n6. 设置中新增“打开应用自动播放”。默认关闭，开启后恢复上次的播放队列和进度。"
}
```

`apkUrl` 使用同目录文件名即可，客户端会保留多层中文父目录并单独获取安装包签名。访客受基本路径限制时接口请求 `/update.json` 或 `/安装包名.apk`；访客使用根目录时请求完整存储路径。下载 URL 始终保留完整实际目录。

也可填写一个完整 HTTP/HTTPS 文件直链，但不要填 AList 的预览网页地址。

客户端下载后先验证 SHA-256，再打开系统安装器。Android 8 及以上首次安装更新时，需要按提示允许本应用“安装未知应用”。检查失败不会影响音乐使用。

## 常见提示

| 提示 | 处理 |
| --- | --- |
| 访客访问尚未启用 | 在 AList 后台启用 guest |
| 更新目录未开放访客读取权限 | 检查 guest 基本路径、更新目录及继承的元信息限制 |
| 更新网盘无法读取文件 | 确认 APK 和 update.json 已上传，路径为 /20T大硬盘/QY_net/feiniu-updates |
| 返回网盘网页或登录页面 | 检查配置是否使用逻辑 /d/ 文件地址，而非网站预览页 |
| APK SHA-256 校验失败 | 重新上传完整 APK，并用脚本重新生成对应清单 |
| 系统提示无法覆盖安装 | 对照实际已安装旧包检查签名、包名和 versionCode |

## 公网验证

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug -PliveUpdateTest=true --console=plain
```

`LiveAlistUpdateTest` 使用实际客户端的清单解析、签名获取、下载和 SHA-256 校验方法读取公网文件，不执行安装。默认单元测试会跳过这个联网测试，仅显式提供上述参数时运行。

0.3.8 的历史测试共 30 项通过，其中包含实际客户端公网下载校验，服务器当时为 `0.3.7-alist-update`。0.3.9 共 35 项通过，重点验证真实 FN ID 登录、Android 4 证书、曲库和播放；未重复运行公网更新下载用例。

0.3.11 共 42 项测试通过、2 项真实联网用例按默认条件跳过，包含 500 首歌单加载及队列、短页继续加载、新版提示与页面切换回归。

0.3.12 历史测试共 45 项通过、2 项真实联网用例按默认条件跳过，新增自动全量加载、播放页后台补齐队列、未知总数与重复分页、替换队列后的隔离回归；Lint 0 个错误。

0.3.16 汇总控制栏间距与背景优化、封面加载修复及转码首播/404 优化，保留此前侧栏、字体与导航恢复改进。发布文件生成到 `release/update-upload/`；其中 `update.json` 的版本、文件名、最低系统和 SHA-256 均从最终 APK 提取。仅生成文件不会改变 AList 在线版本，仍须按上述顺序先上传新 APK、最后替换清单。

2026-11-17 已发布 `0.3.19-playback-fixes / 26` 至上述 AList 更新目录。发布后通过访客读取核对远端 APK 和清单摘要，实际客户端联网更新测试也已通过。

2026-11-25 已发布 `0.3.20-login-fixes / 27`。访客下载核对与客户端实测均通过。
