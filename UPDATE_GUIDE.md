# AList 在线更新发布指南

当前版本：`0.3.12-auto-load`，`versionCode 19`。登录页和曲库页均可点击“检查更新”；登录页、曲库页和播放页恢复前台时按 12 小时间隔自动联网检查。更新不依赖用户登录飞牛 NAS。

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

本次构建沿用当前调试签名，并与从你的公网目录实际下载的 `versionCode 14` APK 核对一致。对已经分发给用户的其它 APK，应使用那份实际旧包核对；同包名、同签名且更高的 `versionCode` 才能覆盖安装。不要在已有用户开始更新后临时更换签名密钥。

## 每次发布

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
       -PreviousApk "上一版实际分发的.apk" `
       -Changelog "1. 更新说明第一条；2. 更新说明第二条。"
   ```

   脚本会从 APK 读取真实包名、版本和最低 Android 版本，验证 APK 签名及新旧包一致性，计算 SHA-256，然后生成：

   ```text
   release/update-upload/
     FeiniuCarMusic-0.3.14-streaming-21.apk
     update.json
   ```

   可以用 `-ChangelogFile "更新说明.txt"` 读取多行 UTF-8 说明，或用 `-OutputDirectory` 指定另一个输出目录。相同版本文件名已存在不同 APK 时脚本会拒绝覆盖；每次对外发布都应递增版本。

4. 将生成的 APK 上传到 AList `/20T大硬盘/QY_net/feiniu-updates`，确认上传完成后，最后替换同目录的 `update.json`。保留不同版本的 APK 文件名，避免旧清单引用的包被替换。
5. 用无痕浏览器以访客打开发布目录，确认可以下载文件，再在应用里点击“检查更新”。直接访问未带签名的 `/d/` 地址可能返回签名错误，这是 AList 下载保护；客户端会先取签名。

## 更新清单

```json
{
  "versionCode": 21,
  "versionName": "0.3.14-streaming",
  "apkUrl": "FeiniuCarMusic-0.3.14-streaming-21.apk",
  "sha256": "由发布脚本生成的64位SHA256",
  "minSdk": 14,
  "changelog": "1. MP3 可选128/192/256/320 kbps，默认192，显示实际码率\n2. Android6及以上边转码边下载边播放，旧机保留完整文件兼容方式\n3. 下载中跳转保留后台任务，完整后可靠定位\n4. 优化暂停恢复、取消换曲、错误提示和不同码率缓存复用"
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

本轮 0.3.12 共 45 项测试通过、2 项真实联网用例按默认条件跳过，新增自动全量加载、播放页后台补齐队列、未知总数与重复分页、替换队列后的隔离回归；Lint 0 个错误。新版 APK 与清单已生成并核对上一版签名，尚未上传，上传后才会对外生效。
