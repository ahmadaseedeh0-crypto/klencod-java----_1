package com.zombie.game;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Random;

public class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        gameView = new GameView(this);
        setContentView(gameView);
        hideSystemUi();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pause();
    }

    static class GameView extends SurfaceView implements Runnable, SurfaceHolder.Callback {

        static class Zombie { float x, y, hp, speed; }
        static class Bullet { float x, y, vx, vy, life; }

        private Thread thread;
        private volatile boolean running = false;
        private final SurfaceHolder holder;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final float d; // density, so sizes look the same on every screen
        private volatile int w = 1, h = 1;

        // Game state (touched only from the game thread, except the volatile input flags)
        private float px, py, hp, dirX = 1, dirY = 0;
        private final ArrayList<Zombie> zombies = new ArrayList<>();
        private final ArrayList<Bullet> bullets = new ArrayList<>();
        private int score, best, wave, toSpawn;
        private float spawnTimer, cooldown, hurtFlash;
        private boolean gameOver;
        private volatile boolean needReset = true;

        // Input
        private int joyId = -1, fireId = -1;
        private float joyBX, joyBY, knobX, knobY;
        private volatile float moveX, moveY;
        private volatile boolean firing;

        public GameView(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            holder = getHolder();
            holder.addCallback(this);
            setFocusable(true);
        }

        // ---------- lifecycle ----------
        public void resume() {
            if (running) return;
            running = true;
            thread = new Thread(this, "game-loop");
            thread.start();
        }

        public void pause() {
            running = false;
            if (thread != null) {
                try { thread.join(); } catch (InterruptedException ignored) {}
                thread = null;
            }
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {}
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            w = width; h = height;
            needReset = true;
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {}

        // ---------- loop ----------
        @Override
        public void run() {
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = Math.min((now - last) / 1_000_000_000f, 0.05f); // works at 60/90/120Hz
                last = now;

                if (!holder.getSurface().isValid()) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    continue;
                }
                update(dt);
                Canvas c = null;
                try {
                    c = holder.lockHardwareCanvas(); // GPU canvas = smooth on phone
                    if (c != null) draw(c);
                } catch (Exception ignored) {
                } finally {
                    if (c != null) holder.unlockCanvasAndPost(c);
                }
            }
        }

        // ---------- logic ----------
        private void reset() {
            zombies.clear();
            bullets.clear();
            px = w / 2f; py = h / 2f;
            hp = 100; score = 0; wave = 1; toSpawn = 6;
            spawnTimer = 1f; cooldown = 0; hurtFlash = 0;
            gameOver = false;
            needReset = false;
        }

        private void update(float dt) {
            if (needReset) reset();
            if (gameOver) return;

            // move player
            float speed = 170 * d;
            px = clamp(px + moveX * speed * dt, 18 * d, w - 18 * d);
            py = clamp(py + moveY * speed * dt, 18 * d, h - 18 * d);
            if (Math.abs(moveX) + Math.abs(moveY) > 0.1f) {
                float len = (float) Math.hypot(moveX, moveY);
                dirX = moveX / len; dirY = moveY / len;
            }

            // shooting (auto-aims at nearest zombie)
            cooldown -= dt;
            if (firing && cooldown <= 0) {
                cooldown = 0.18f;
                Zombie near = null; float best2 = Float.MAX_VALUE;
                for (Zombie z : zombies) {
                    float dx = z.x - px, dy = z.y - py, dd = dx * dx + dy * dy;
                    if (dd < best2) { best2 = dd; near = z; }
                }
                float ax = dirX, ay = dirY;
                if (near != null) {
                    float len = (float) Math.sqrt(best2);
                    if (len > 0.001f) { ax = (near.x - px) / len; ay = (near.y - py) / len; }
                }
                Bullet b = new Bullet();
                b.x = px; b.y = py; b.vx = ax * 560 * d; b.vy = ay * 560 * d; b.life = 1.2f;
                bullets.add(b);
            }

            // bullets
            for (int i = bullets.size() - 1; i >= 0; i--) {
                Bullet b = bullets.get(i);
                b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
                boolean remove = b.life <= 0 || b.x < 0 || b.y < 0 || b.x > w || b.y > h;
                if (!remove) {
                    for (int j = zombies.size() - 1; j >= 0; j--) {
                        Zombie z = zombies.get(j);
                        float dx = z.x - b.x, dy = z.y - b.y, r = 18 * d;
                        if (dx * dx + dy * dy < r * r) {
                            z.hp -= 1;
                            remove = true;
                            if (z.hp <= 0) { zombies.remove(j); score += 10; }
                            break;
                        }
                    }
                }
                if (remove) bullets.remove(i);
            }

            // zombies chase + bite
            boolean bitten = false;
            for (Zombie z : zombies) {
                float dx = px - z.x, dy = py - z.y;
                float len = (float) Math.hypot(dx, dy);
                if (len > 0.001f) { z.x += dx / len * z.speed * dt; z.y += dy / len * z.speed * dt; }
                if (len < 32 * d) { hp -= 18 * dt; bitten = true; }
            }
            hurtFlash = bitten ? 1f : Math.max(0, hurtFlash - dt * 3);

            // spawning + waves
            spawnTimer -= dt;
            if (toSpawn > 0 && spawnTimer <= 0) {
                spawnTimer = Math.max(0.35f, 1.2f - wave * 0.08f);
                toSpawn--;
                Zombie z = new Zombie();
                int side = rnd.nextInt(4);
                if (side == 0) { z.x = -20 * d; z.y = rnd.nextFloat() * h; }
                else if (side == 1) { z.x = w + 20 * d; z.y = rnd.nextFloat() * h; }
                else if (side == 2) { z.x = rnd.nextFloat() * w; z.y = -20 * d; }
                else { z.x = rnd.nextFloat() * w; z.y = h + 20 * d; }
                z.hp = 2 + wave / 3;
                z.speed = Math.min(50 + wave * 6, 115) * d * (0.85f + rnd.nextFloat() * 0.3f);
                zombies.add(z);
            }
            if (toSpawn == 0 && zombies.isEmpty()) {
                wave++;
                toSpawn = 5 + wave * 3;
                hp = Math.min(100, hp + 15);
                spawnTimer = 2f;
            }

            if (hp <= 0) {
                hp = 0; gameOver = true;
                best = Math.max(best, score);
            }
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        // ---------- drawing ----------
        private void draw(Canvas c) {
            c.drawColor(Color.rgb(24, 32, 24));

            // ground grid
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(Color.argb(40, 255, 255, 255));
            for (float x = 0; x < w; x += 60 * d) c.drawLine(x, 0, x, h, p);
            for (float y = 0; y < h; y += 60 * d) c.drawLine(0, y, w, y, p);

            p.setStyle(Paint.Style.FILL);

            // zombies
            for (Zombie z : zombies) {
                p.setColor(Color.rgb(90, 160, 70));
                c.drawCircle(z.x, z.y, 16 * d, p);
                p.setColor(Color.RED);
                c.drawCircle(z.x - 5 * d, z.y - 4 * d, 2.5f * d, p);
                c.drawCircle(z.x + 5 * d, z.y - 4 * d, 2.5f * d, p);
            }

            // bullets
            p.setColor(Color.YELLOW);
            for (Bullet b : bullets) c.drawCircle(b.x, b.y, 4 * d, p);

            // player
            p.setColor(Color.rgb(60, 140, 255));
            c.drawCircle(px, py, 15 * d, p);
            p.setColor(Color.WHITE);
            p.setStrokeWidth(4 * d);
            c.drawLine(px, py, px + dirX * 24 * d, py + dirY * 24 * d, p);

            // damage flash
            if (hurtFlash > 0) {
                p.setColor(Color.argb((int) (70 * hurtFlash), 255, 0, 0));
                c.drawRect(0, 0, w, h, p);
            }

            // joystick
            if (joyId != -1) {
                p.setColor(Color.argb(60, 255, 255, 255));
                c.drawCircle(joyBX, joyBY, 60 * d, p);
                p.setColor(Color.argb(140, 255, 255, 255));
                c.drawCircle(joyBX + knobX, joyBY + knobY, 25 * d, p);
            }

            // fire button hint
            float bx = w - 90 * d, by = h - 90 * d;
            p.setColor(firing ? Color.argb(180, 255, 80, 80) : Color.argb(90, 255, 80, 80));
            c.drawCircle(bx, by, 48 * d, p);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(16 * d);
            c.drawText("FIRE", bx, by + 6 * d, p);

            // HUD
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(18 * d);
            c.drawText("Score: " + score + "   Wave: " + wave + "   Best: " + best, 24 * d, 34 * d, p);
            p.setColor(Color.argb(120, 0, 0, 0));
            c.drawRect(24 * d, 44 * d, 224 * d, 58 * d, p);
            p.setColor(hp > 35 ? Color.rgb(80, 200, 80) : Color.rgb(220, 60, 60));
            c.drawRect(24 * d, 44 * d, (24 + 2 * hp) * d, 58 * d, p);

            if (gameOver) {
                p.setColor(Color.argb(170, 0, 0, 0));
                c.drawRect(0, 0, w, h, p);
                p.setColor(Color.WHITE);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(40 * d);
                c.drawText("GAME OVER", w / 2f, h / 2f - 10 * d, p);
                p.setTextSize(18 * d);
                c.drawText("Score " + score + "  -  tap to play again", w / 2f, h / 2f + 28 * d, p);
            }
        }

        // ---------- touch ----------
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            int idx = e.getActionIndex();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int id = e.getPointerId(idx);
                    float x = e.getX(idx), y = e.getY(idx);
                    if (gameOver) { needReset = true; return true; }
                    if (x < w / 2f && joyId == -1) {
                        joyId = id; joyBX = x; joyBY = y; knobX = knobY = 0; moveX = moveY = 0;
                    } else if (x >= w / 2f && fireId == -1) {
                        fireId = id; firing = true;
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        if (e.getPointerId(i) == joyId) {
                            float dx = e.getX(i) - joyBX, dy = e.getY(i) - joyBY;
                            float max = 60 * d, len = (float) Math.hypot(dx, dy);
                            if (len > max) { dx = dx / len * max; dy = dy / len * max; }
                            knobX = dx; knobY = dy;
                            moveX = dx / max; moveY = dy / max;
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int id = e.getPointerId(idx);
                    if (id == joyId) { joyId = -1; moveX = moveY = 0; knobX = knobY = 0; }
                    if (id == fireId) { fireId = -1; firing = false; }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    joyId = fireId = -1; moveX = moveY = 0; firing = false;
                    break;
            }
            return true;
        }
    }
}