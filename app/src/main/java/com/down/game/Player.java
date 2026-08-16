package com.down.game;

public class Player {

    public float x = 640, y = 640;   // world position
    public float speed = 260;        // px per second
    public int facing = 1;           // 1 right, -1 left
    public float bobTime = 0;
    public float attackTime = -1;    // <0 = not attacking
    public final float attackDuration = 0.9f;

    public boolean isAttacking() { return attackTime >= 0; }

    public void startAttack() {
        if (!isAttacking()) attackTime = 0;
    }

    public void update(float dt, float mx, float my) {
        bobTime += dt;
        if (isAttacking()) {
            attackTime += dt;
            if (attackTime > attackDuration) attackTime = -1;
        } else {
            x += mx * speed * dt;
            y += my * speed * dt;
            if (mx < -0.05f) facing = -1;
            if (mx >  0.05f) facing =  1;
        }
    }
}
