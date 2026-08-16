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

    // turn action: 0 = move, 1 = attack, 2 = settle, 3 = done
    public int act = 0;
    public boolean planned = false, planMove = false, acted = false;
    public float tx, ty;

    public boolean attacking() { return attackT >= 0; }

    public void resetTurn() {
        act = 0; planned = false; planMove = false; acted = false;
        attackT = -1; struck = false;
    }

    public void turnUpdate(float dt, float px, float py) {
        animT += dt;

        float dx = px - x, dy = py - y;
        float dist = (float) Math.hypot(dx, dy);

        switch (act) {
            case 0:
                if (planMove) {
                    float d = (float) Math.hypot(tx - x, ty - y);
                    if (d > 6) {
                        floater.moving = true;
                        float step = Math.min(d, speed * dt);
                        x += (tx - x) / d * step;
                        y += (ty - y) / d * step;
                        if (tx - x < -0.05f) facing = -1;
                        if (tx - x >  0.05f) facing =  1;
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
                    if (attackT < 0) {
                        if (dist < 140) attackT = 0;
                        else acted = true;
                    } else {
                        attackT += dt;
                        if (attackT > 0.9f) { attackT = -1; acted = true; }
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
