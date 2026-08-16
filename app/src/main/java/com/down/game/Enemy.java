package com.down.game;

public class Enemy {

    public float x, y;
    public int hp = 30, maxHp = 30;
    public float speed = 150;
    public int facing = 1;
    public float animT = (float) (Math.random() * 10);
    public float hitFlash = 0;
    public boolean dead = false;
    public float deathT = 0;
    public float attackT = -1;
    public float cooldown = 1 + (float) Math.random();
    public boolean struck = false;

    public boolean attacking() { return attackT >= 0; }

    public void update(float dt, float px, float py) {
        animT += dt;
        if (hitFlash > 0) hitFlash -= dt;
        if (dead) { deathT += dt; return; }

        float dx = px - x, dy = py - y;
        float dist = (float) Math.hypot(dx, dy);

        if (attacking()) {
            attackT += dt;
            if (attackT > 0.8f) { attackT = -1; cooldown = 1.2f; struck = false; }
        } else {
            cooldown -= dt;
            if (dist > 100) {
                x += dx / dist * speed * dt;
                y += dy / dist * speed * dt;
                if (dx < -0.05f) facing = -1;
                if (dx >  0.05f) facing =  1;
            } else if (cooldown <= 0) {
                attackT = 0;
            }
        }
    }
}
