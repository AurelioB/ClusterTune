package com.aure.clustertune.testapps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;

/** Four independent packages. No permissions, networking, or persistent state. */
public final class LetterActivity extends Activity {
    private LetterView letter;
    private boolean running;
    private boolean renderFrames;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (running && renderFrames) {
                letter.invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        letter = new LetterView(this, getString(R.string.app_letter));
        setContentView(letter);
        renderFrames = getIntent().getBooleanExtra("renderFrames", false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderFrames = intent.getBooleanExtra("renderFrames", false);
        Choreographer.getInstance().removeFrameCallback(frame);
        if (running && renderFrames) Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        if (renderFrames) Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override protected void onPause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        super.onPause();
    }

    private static final class LetterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String text;
        LetterView(Context context, String text) {
            super(context);
            this.text = text;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            setContentDescription("ClusterTune test app " + text);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            paint.setTextSize(Math.min(getWidth(), getHeight()) * 0.5f);
            canvas.drawText(text, getWidth() / 2f,
                    getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
        }
    }
}
