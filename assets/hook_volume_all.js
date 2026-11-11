/*
 * P145 音量 Hook（高级设置 APK 内置版）
 *   com.android.car             → getSuggestedAudioUsage 恒返回媒体
 *   com.flyme.auto.keyserver    → getCurrentActiveStreamEntity 恒返回媒体组
 *   com.flyme.auto.knobservice  → CarAudioManager.setGroupVolume 非 0 改 0
 */
var BASE = '/data/local/p145adv/';

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
    console.log('[VOL] process=' + pkg);

    if (pkg === 'com.android.car') {
        try {
            var S = Java.use('com.android.car.CarAudioService');
            S.getSuggestedAudioUsage.implementation = function () {
                hit('CAR usage=media');
                return 1; // USAGE_MEDIA
            };
            console.log('[CAR] hook ok');
        } catch (e) {
            console.log('[CAR] fail ' + e);
        }
    } else if (pkg === 'com.flyme.auto.keyserver') {
        try {
            var W = Java.use('com.flyme.auto.keyserver.manager.CarAudioManagerWrapper');
            var orig = W.getCurrentActiveStreamEntity;
            W.getCurrentActiveStreamEntity.overload('android.content.Context').implementation = function (ctx) {
                try {
                    var mgr = this.mCarAudioManager.value;
                    var ent = this.initStreamEntityIfNeed(mgr, 1);
                    if (ent !== null) {
                        hit('KEY group=' + ent.getGroupId());
                        return ent;
                    }
                } catch (e2) {
                    console.log('[KEY] err ' + e2);
                }
                return orig.call(this, ctx);
            };
            console.log('[KEY] hook ok');
        } catch (e) {
            console.log('[KEY] fail ' + e);
        }
    } else if (pkg === 'com.flyme.auto.knobservice') {
        try {
            var M = Java.use('android.car.media.CarAudioManager');
            M.setGroupVolume.overload('int', 'int', 'int').implementation = function (g, idx, flags) {
                if (g !== 0) {
                    hit('KNOB ' + g + '->0');
                    return this.setGroupVolume(0, idx, flags);
                }
                return this.setGroupVolume(g, idx, flags);
            };
            console.log('[KNOB] hook ok');
        } catch (e) {
            console.log('[KNOB] fail ' + e);
        }
    } else {
        console.log('[VOL] skip');
    }
});
