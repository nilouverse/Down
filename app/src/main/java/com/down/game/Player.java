package com.down.game;

public class Player {

    public float x = 640, y = 640;
    public float targetX = 640, targetY = 640;
    public float speed = 300;
    public int facing = 1;
    public float bobTime = 0;
    public Hero hero;
    public int actionsLeft = 2;
    public int hp = 100;
    public boolean cried = false;

    // input buffer: queued move consumed on arrival (C1)
    public float qX, qY, qT;

    public boolean isAttacking() { return hero != null && hero.attacking(); }

    public boolean isMoving() {
        float dx = targetX - x, dy = targetY - y;
        return (dx * dx + dy * dy) > 16f;
    }

    public void setTarget(float tx, float ty) { targetX = tx; targetY = ty; }

    public void queueTarget(float tx, float ty) { qX = tx; qY = ty; qT = 0.18f; }

    public void clearQueue() { qT = 0f; }

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
        }

        if (qT > 0) {
            qT -= dt;
            if (qT > 0 && !isMoving() && !isAttacking()) {
                setTarget(qX, qY);
                qT = 0f;
            }
        }
    }
}
