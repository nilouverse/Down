package com.down.game;

public class Player {

    public float x = 640, y = 640;        // world position (feet)
    public float targetX = 640, targetY = 640;
    public float speed = 300;             // glide speed px/s
    public int facing = 1;
    public float bobTime = 0;
    public float attackTime = -1;
    public final float attackDuration = 0.9f;

    public boolean isAttacking() { return attackTime >= 0; }

    public boolean isMoving() {
        float dx = targetX - x, dy = targetY - y;
        return (dx * dx + dy * dy) > 16;
    }

    public void setTarget(float tx, float ty) { targetX = tx; targetY = ty; }

    public void startAttack() { if (!isAttacking()) attackTime = 0; }

    public void update(float dt) {
        bobTime += dt;
        if (isAttacking()) {
            attackTime += dt;
            if (attackTime > attackDuration) attackTime = -1;
        } else {
            float dx = targetX - x, dy = targetY - y;
            float dist = (float) Math.hypot(dx, dy);
            if (dist > 4) {
                float step = Math.min(dist, speed * dt);
                x += dx / dist * step;
                y += dy / dist * step;
                if (dx < -0.05f) facing = -1;
                if (dx >  0.05f) facing =  1;
            }
        }
    }
}
