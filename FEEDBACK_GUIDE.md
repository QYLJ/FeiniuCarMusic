# 运行日志与 AList 反馈指南

应用会在私有目录维护不超过约 1 MB 的滚动运行日志，记录页面切换、登录结果、曲库加载、播放准备/音频焦点、在线更新和未捕获崩溃。日志写入前会遮盖密码、Token、Cookie、签名等常见敏感字段。

登录页和“设置 → 问题反馈与运行日志”都能提交反馈。用户可选择是否附带近期日志；每份证据还包含应用版本、车机型号、Android 版本、网络类型和内存/存储状态，但不主动采集密码、登录令牌、Cookie、Android ID 或音乐文件。

## 隐私与证据链

1. 应用先在私有目录生成 JSON 证据，再用随机 AES-256 密钥加密。
2. AES/HMAC 密钥由内置 RSA-2048 公钥封装；完整文件使用 HMAC-SHA256 防篡改。
3. 加密文件先落到 `files/feedback/pending`，AList 确认接收后才删除，并在本机保存反馈编号、远程路径、字节数和 SHA-256 摘要。
4. 上传失败时保留最多 5 份加密证据，用户可在反馈页重试。
5. AList 收到的文件扩展名为 `.fnfeedback`；没有私钥无法读取正文或日志。

公钥已写入安装包。匹配的私钥只保存在本机、已由 `.gitignore` 排除：

```text
.feedback-keys/feedback-private-key.pem
```

请立刻把私钥备份到密码库或离线介质。丢失后，已上传的反馈无法恢复；泄露后应生成新密钥、替换安装包公钥并发布新版本。

## AList 一次性配置

当前公网 AList `v3.37.4` 的 guest 基本路径是 `/20T大硬盘/QY_net/feiniu-updates`，权限值为 `8`，`/feedback` 目录及写入规则已经配置。2026-10-17 的真实验收已确认应用可上传、访客可回读加密文件、SHA-256 一致并可由本机私钥解密。以后迁移或重建 AList 时按下列清单恢复：

1. 在更新目录下创建 `feedback` 文件夹。
2. 给 `guest` 只增加“新建目录或上传”权限，不增加重命名、移动、复制、删除、WebDAV 管理或后台管理权限。
3. 给 `/20T大硬盘/QY_net/feiniu-updates/feedback` 添加元信息，并打开 `写入`；不要给更新目录根路径开放写入。
4. 保持反馈文件大小受反向代理和 AList 限制。客户端单份上限为 1 MB，且本机最多保留 5 份待上传文件。
5. 完成后从应用提交一次写着“反馈通道验收”的反馈，确认 AList 出现 `FeiniuCarMusic-feedback-<编号>.fnfeedback`，再用下方脚本解密核对。

本次真实验收记录：

```text
反馈编号：20261017-121613-fd1e063f7c84
远程文件：/feedback/FeiniuCarMusic-feedback-20261017-121613-fd1e063f7c84.fnfeedback
文件大小：1688 bytes
SHA-256：d5c47421126d677511c97ef9b17c038d42bdbc97979bd327e334ad49777597de
结果：上传成功；远端回读大小与摘要一致；HMAC 校验及 RSA/AES 解密成功；正文、版本和运行日志完整；测试敏感串未出现。
```

应用上传接口为：

```text
PUT http://ikuai.qiuxiaoyu.top:5244/api/fs/put
File-Path: URL 编码后的 /feedback/FeiniuCarMusic-feedback-<编号>.fnfeedback
Content-Type: application/octet-stream
```

客户端不保存 AList 管理员账号、密码或 Token。AList 官方文档说明 guest 上传需同时具备上传权限与目录写入元信息；这两项缺一时，客户端会安全保留证据并提示稍后重试。

开发时可显式运行真实反馈验收；默认单元测试会跳过该联网测试，避免日常构建向线上目录写入文件：

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.feiniu.carmusic.feedback.LiveAlistFeedbackTest" `
  -PliveFeedbackTest=true
```

## 解密反馈

从 AList 下载 `.fnfeedback` 后运行：

```powershell
.\tools\decrypt-feedback.ps1 `
  -InputFile .\download\FeiniuCarMusic-feedback-20261017-xxxx.fnfeedback
```

默认读取 `.feedback-keys/feedback-private-key.pem`，并在输入文件旁生成 `.json`。也可通过 `-PrivateKeyPath` 指定备份私钥，通过 `-OutputFile` 指定输出位置。脚本会先验证 HMAC；文件损坏、被修改或私钥不匹配时不会输出伪造证据。
