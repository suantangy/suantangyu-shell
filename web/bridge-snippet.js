<!--
  宝宝巴士原生壳桥接脚本（bridge-snippet.js）
  用法：把下面 <script> 块原样粘贴进你的 index.html（建议放在 </body> 之前），
  然后按注释把三处函数名换成你自己的播放控制函数名，并在切歌/播放暂停处
  各加一行调用。网页在普通浏览器里打开时 window.BaoBus 不存在，全部自动
  跳过，不影响现有行为。
-->
<script>
(function () {
  // 上报当前曲目（标题/歌手/封面）→ 原生通知栏
  window.baoPostTrack = function () {
    try {
      if (!window.BaoBus) return;
      var m = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
      BaoBus.postTrack(
        m ? (m.title || '') : '',
        m ? (m.artist || '') : '',
        (m && m.artwork && m.artwork[0]) ? (m.artwork[0].src || '') : ''
      );
    } catch (e) {}
  };

  // 上报播放/暂停状态 → 原生通知栏图标切换
  window.baoPostState = function () {
    try {
      if (!window.BaoBus) return;
      var st = navigator.mediaSession ? navigator.mediaSession.playbackState : 'none';
      BaoBus.postState(st === 'playing');
    } catch (e) {}
  };

  // 原生通知栏按钮回调：把右边的三个函数名换成你自己的
  window.__baoCtl = function (a) {
    try {
      if (a === 'toggle') {
        /* TODO: 换成你的 播放/暂停 切换函数，例如 togglePlay() */ togglePlay();
      } else if (a === 'next') {
        /* TODO: 换成你的 下一首 函数，例如 nextSong() */ nextSong();
      } else if (a === 'prev') {
        /* TODO: 换成你的 上一首 函数，例如 prevSong() */ prevSong();
      }
    } catch (e) {}
  };

  /*
   * 接线（在你 index.html 里对应的位置各加一行）：
   * 1. 切歌成功、mediaSession.metadata 赋值之后：  baoPostTrack(); baoPostState();
   * 2. audio 的 play 事件里：                      baoPostState();
   * 3. audio 的 pause 事件里：                     baoPostState();
   * 找不到位置时最省事的兜底：在播放页定时器里每秒调一次 baoPostState()。
   */
})();
</script>
