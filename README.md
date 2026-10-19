# 宝宝巴士原生壳（混合版）

WebView 承载你现有的网页播放器（`https://yinyue-bfq.pages.dev/`），
原生 `ForegroundService` 负责后台保活 + 通知栏常驻媒体控制。
音频仍由网页的 `<audio>` 播放，你的网页代码一行不用改逻辑，
只需粘贴桥接脚本并接三处函数名。

## 手机上完成打包（不用电脑）

1. 手机浏览器打开 github.com，注册/登录 GitHub。
2. 新建仓库（比如 `baobao-shell`），选 Public。
3. 用仓库页的 **Add file → Upload files**，把本目录**所有文件**
   （含 `.github` 隐藏目录）一次性传上去，Commit 到 `main` 分支。
4. 打开仓库的 **Actions** 标签页，点 **Build debug APK → Run workflow**
   （或等 push 自动触发），等约 5–8 分钟变绿。
5. 点进那次运行，最下方 **Artifacts** 下载 `baobao-debug-apk`，
   解压得到 `app-debug.apk`，手机直接安装。
   （首次安装会有"未知来源"提示，允许即可；这是 debug 签名，
   发给朋友装也没问题。）

## 网页侧接线（index.html，加一次就行）

1. 把 `web/bridge-snippet.js` 里 `<script>` 块粘贴进 `index.html`
  （`</body>` 之前）。
2. 把 `__baoCtl` 里 `togglePlay()/nextSong()/prevSong()` 换成你自己的
   三个函数名。
3. 在切歌处加 `baoPostTrack(); baoPostState();`，
   在 `audio` 的 `play` / `pause` 事件里加 `baoPostState();`。
4. 重新部署 Pages 即可。在桌面浏览器打开网页时桥不存在，自动跳过。

## 改 WebView 地址

`app/src/main/java/com/baobao/player/MainActivity.kt` 顶部
`START_URL` 常量。

## 达到的效果

- 熄屏、切后台：前台服务拿住 `mediaPlayback` 身份，进程不被杀，
  播放继续，通知栏常驻上一首/播放暂停/下一首。
- 点通知栏回到 App；通知栏按钮直接控制网页播放。
- 电池优化：手机设置里把"宝宝巴士"的电池优化设为"不优化"，
  保活更稳。

## 做不到的事（实话）

- **从最近任务划掉 App 会停播**：WebView 随 Activity 销毁，
  音频管线还在网页里。要划掉也不停，需要把解码搬进服务用
  ExoPlayer 播——那要重写音频管线，是二期的大工程。
- 通知栏封面图暂未加载（只显示文字），二期可加。
