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

    public boolean isAttacking() { return hero != null && hero.attacking(); }

    public boolean isMoving() {
        float dx = targetX - x, dy = targetY - y;
        return (dx * dx + dy * dy) > 16f;
    }

    public void setTarget(float tx, float ty) { targetX = tx; targetY = ty; }

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
    }
}
