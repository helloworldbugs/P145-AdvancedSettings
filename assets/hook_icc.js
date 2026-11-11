/*
 * P145 ICC 播报屏蔽（高级设置 APK 内置版）
 * 目标进程：com.baidu.che.codriver
 * 命中文本：智能巡航已退出 → 丢弃，不合成、不播放、不申请音频焦点
 */
var BASE = '/data/local/p145adv/';
var TARGET = '智能巡航已退出';

function hit(s) {
    try {
        var line = '' + new Date().getTime() + ' ' + s + '\n';
        var fos = Java.use('java.io.FileOutputStream').$new(BASE + 'hits.log', true);
        fos.write(Java.use('java.lang.String').$new(line).getBytes());
        fos.close();
    } catch (e) {}
}

Java.perform(function () {
    var pkg = '';
    try {
        pkg = Java.use('android.app.ActivityThread').currentProcessName();
    } catch (e) {}
    console.log('[ICC] process=' + pkg);

    if (pkg !== 'com.baidu.che.codriver') {
        console.log('[ICC] skip (not DuerOS)');
        return;
    }

    try {
        var C = Java.use('com.baidu.dueros.conversation.SpeechManagerImpl');
        C.speakTts.overload('java.lang.String', 'boolean', 'int').implementation = function (t, b, i) {
            if (t && t.indexOf(TARGET) >= 0) {
                hit('ICC DROP');
                console.log('[ICC] DROP ' + t);
                return;
            }
            return this.speakTts(t, b, i);
        };
        console.log('[ICC] hook ok');
    } catch (e) {
        console.log('[ICC] fail ' + e);
    }
});
