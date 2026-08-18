package com.down.game;

public class Enemy {

    public float x, y;
    public int hp = 30, maxHp = 30;
    public float speed = 220;
    public int facing = 1;
    public float animT = (float) (Math.random() * 10);
    public float hitFlash = 0;
    public boolean dead = false;
    public float deathT = 0;
    public float attackT = -1;
    public boolean struck = false;
    public final Floater floater = new Floater();

    public int act = 0;
    public boolean planned = false, planMove = false, acted = false;
    public float tx, ty;
    public boolean glowing = false;

    public static final float GLOW_DUR = 0.5f;
    public static final float ATK_DUR = 0.9f;

    public boolean attacking() { return attackT >= 0 || glowing; }

    public void resetTurn() {
        act = 0; planned = false; planMove = false; acted = false;
        attackT = -1; struck = false; glowing = false;
    }

    public void turnUpdate(float dt, float px, float py, boolean adjacent) {
        switch (act) {
            case 0:
                if (planMove) {
                    float dx = tx - x, dy = ty - y;
                    float distSq = dx * dx + dy * dy;
                    if (distSq > 36f) {
                        floater.moving = true;
                        float dist = (float) Math.sqrt(distSq);
                        float step = Math.min(dist, speed * dt);
                        x += dx / dist * step;
                        y += dy / dist * step;
                        if (dx < -0.05f) facing = -1;
                        if (dx >  0.05f) facing =  1;
                    } else {
                        planMove = false;
                        floater.moving = false;
                    }
                } else if (planned) {
                    floater.moving = false;
                    if (floater.state == 0) act = 1;
                }
                break;
            case 1:
                floater.moving = false;
                if (!acted) {
                    if (attackT < 0 && !glowing) {
                        if (adjacent) {
                            glowing = true;
                            attackT = 0;
                            facing = px >= x ? 1 : -1;
                        } else acted = true;
                    } else if (glowing) {
                        attackT += dt;
                        if (attackT > GLOW_DUR) { glowing = false; attackT = 0; }
                    } else {
                        attackT += dt;
                        if (attackT > ATK_DUR) { attackT = -1; acted = true; }
                    }
                } else {
                    act = 2;
                }
                break;
            case 2:
                if (floater.state == 0) act = 3;
                break;
        }
        floater.update(dt);
    }
}
