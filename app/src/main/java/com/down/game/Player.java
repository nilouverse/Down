package com.down.game;

public class Player {
    public float x = 640, y = 640;
    public float targetX = 640, targetY = 640;
    public float speed = 300;
    public int facing = 1;
    public float bobTime = 0;
    public Hero hero;
    public int actionsLeft = 3;
    public int hp = 100;
    public boolean cried = false;

    // Waypoints for path-constrained movement (hex-by-hex)
    public float[] wpx = new float[16];
    public float[] wpy = new float[16];
    public int wpI = 0, wpLen = 0;

    // Input buffer (hex coords)
    public int qQ = Integer.MIN_VALUE, qR = Integer.MIN_VALUE;
    public float qT = 0;

    public boolean isAttacking() { return hero != null && hero.attacking(); }

    public boolean isMoving() {
        if (wpI < wpLen) return true;
        float dx = targetX - x, dy = targetY - y;
        return (dx * dx + dy * dy) > 16f;
    }

    public void setTarget(float tx, float ty) { targetX = tx; targetY = ty; wpI = 0; wpLen = 0; }

    public void setPath(float[] xs, float[] ys, int n) {
        wpLen = Math.min(n, 16);
        for (int i = 0; i < wpLen; i++) { wpx[i] = xs[i]; wpy[i] = ys[i]; }
        wpI = 0;
        if (wpLen > 0) { targetX = wpx[0]; targetY = wpy[0]; wpI = 1; }
    }

    public void queueHex(int q, int r) { qQ = q; qR = r; qT = 0.18f; }
    public void clearQueue() { qT = 0f; qQ = Integer.MIN_VALUE; }

    public void update(float dt) {
        bobTime += dt;
        if (hero != null) hero.updateAnim(dt, isMoving());

        if (!isAttacking() && isMoving()) {
            float dx = targetX - x, dy = targetY - y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float step = Math.min(dist, speed * dt);
            x += dx / dist * step;
            y += dy / dist * step;
            if (dx < -0.05f) facing = -1;
            if (dx >  0.05f) facing =  1;
            if (dist <= step + 0.001f && wpI < wpLen) {
                targetX = wpx[wpI]; targetY = wpy[wpI]; wpI++;
            }
        }

        if (qT > 0) qT -= dt;
    }
}
