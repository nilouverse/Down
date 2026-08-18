package com.down.game;

public class Enemy {

    public float x, y;
    public int hp = 30, maxHp = 30;
    public int gender = 0;
    public int weapon = 0;
    public boolean cried = false;
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
    public boolean planned = false;
    public int attacksPlanned = 0, attacksDone = 0;
    public float[] pathX = new float[7];
    public float[] pathY = new float[7];
    public int pathLen = 0, pathI = 0;

    public static final float GLOW_DUR = 0.5f;
    public static final float ATK_DUR = 0.9f;

    public boolean attacking() { return attackT >= 0; }

    public void resetTurn() {
        act = 0; planned = false;
        attackT = -1; struck = false;
        attacksPlanned = 0; attacksDone = 0;
        pathLen = 0; pathI = 0;
    }

    public void turnUpdate(float dt, float px, float py, boolean adjacent) {
        switch (act) {
            case 0:
                if (pathI < pathLen) {
                    float dx = pathX[pathI] - x, dy = pathY[pathI] - y;
                    float d2 = dx * dx + dy * dy;
                    if (d2 > 36f) {
                        floater.moving = true;
                        float d = (float) Math.sqrt(d2);
                        float step = Math.min(d, speed * dt);
                        x += dx / d * step;
                        y += dy / d * step;
                        if (dx < -0.05f) facing = -1;
                        if (dx >  0.05f) facing =  1;
                    } else {
                        x = pathX[pathI]; y = pathY[pathI];
                        pathI++;
                        if (pathI >= pathLen) floater.moving = false;
                    }
                } else if (floater.state == 0) {
                    if (attacksPlanned > 0 && adjacent) {
                        act = 1;
                        attackT = 0;
                        struck = false;
                        facing = px >= x ? 1 : -1;
                    } else {
                        act = 3;
                    }
                }
                break;

            case 1:
                floater.moving = false;
                if (attackT >= 0) {
                    attackT += dt;
                    if (attackT > ATK_DUR) {
                        attacksDone++;
                        struck = false;
                        if (attacksDone < attacksPlanned && adjacent) {
                            attackT = 0;
                        } else {
                            attackT = -1;
                            act = 3;
                        }
                    }
                }
                break;
        }
        floater.update(dt);
    }
}
