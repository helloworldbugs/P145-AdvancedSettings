package com.p145.advancedsettings.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 手绘的胶囊开关（圆形滑块 + 渐变色轨道） */
public class PillSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChange(PillSwitch v, boolean checked);
    }

    private static final int COLOR_ON_A = 0xFF22D3EE;
    private static final int COLOR_ON_B = 0xFF3B82F6;
    private static final int COLOR_OFF = 0xFF22303C;
    private static final int COLOR_KNOB = 0xFFF2FAFF;
    private static final int COLOR_KNOB_OFF = 0xFF7E93A6;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean checked;
    private float pos;
    private ValueAnimator anim;
    private OnCheckedChangeListener listener;

    public PillSwitch(Context c) {
        this(c, null);
    }

    public PillSwitch(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean v) {
        setChecked(v, false);
    }

    public void setChecked(boolean v, boolean animate) {
        if (checked == v && pos == (v ? 1f : 0f)) {
            return;
        }
        checked = v;
        if (anim != null) {
            anim.cancel();
        }
        if (!animate) {
            pos = v ? 1f : 0f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, v ? 1f : 0f);
        anim.setDuration(190);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void toggle() {
        setChecked(!checked, true);
        if (listener != null) {
            listener.onCheckedChange(this, checked);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                    toggle();
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, w, h);

        // 外发光
        if (pos > 0.01f) {
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(Color.argb((int) (46 * pos), 34, 211, 238));
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.55f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            RectF g = new RectF(rect);
            g.inset(-r * 0.18f, -r * 0.18f);
            canvas.drawRoundRect(g, r * 1.18f, r * 1.18f, glow);
            glow.setMaskFilter(null);
        }

        // 轨道
        track.setStyle(Paint.Style.FILL);
        if (pos > 0.01f) {
            track.setShader(new LinearGradient(0, 0, w, h, COLOR_ON_A, COLOR_ON_B, Shader.TileMode.CLAMP));
            track.setAlpha((int) (255 * Math.max(pos, 0.35f)));
            canvas.drawRoundRect(rect, r, r, track);
            track.setShader(null);
            track.setAlpha(255);
        } else {
            track.setColor(COLOR_OFF);
            canvas.drawRoundRect(rect, r, r, track);
        }

        // 内描边
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Math.max(1.5f, h * 0.028f));
        track.setColor(pos > 0.5f ? 0x33FFFFFF : 0x1AFFFFFF);
        RectF s = new RectF(rect);
        s.inset(track.getStrokeWidth() / 2f, track.getStrokeWidth() / 2f);
        canvas.drawRoundRect(s, r, r, track);
        track.setStyle(Paint.Style.FILL);

        // 滑块
        float pad = h * 0.115f;
        float kr = r - pad;
        float cx = r + pos * (w - 2 * r);
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(COLOR_KNOB);
        knob.setShadowLayer(h * 0.07f, 0, h * 0.03f, 0x66000000);
        canvas.drawCircle(cx, h / 2f, kr, knob);
        knob.clearShadowLayer();

        // 滑块状态点
        knob.setColor(checked ? 0xFF0891B2 : COLOR_KNOB_OFF);
        canvas.drawCircle(cx, h / 2f, kr * 0.30f, knob);
    }
}
