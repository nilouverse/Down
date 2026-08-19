package com.down.game;

public class SceneActor {
    public String id;
    public String type;
    public float x, y;
    public int q, r;
    public boolean hidden;

    public float targetX, targetY;
    public boolean moving;
    public float speed = 150f;
    public float bobPhase;

    public SceneActor(String id, String type, float x, float y, int q, int r, boolean hidden) {
        this.id = id; this.type = type;
        this.x = x; this.y = y;
        this.q = q; this.r = r;
        this.hidden = hidden;
        this.targetX = x; this.targetY = y;
        this.bobPhase = (float) Math.random() * 6f;
    }

    public void update(float dt) {
        if (moving) {
            float dx = targetX - x;
            float dy = targetY - y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < speed * dt) {
                x = targetX; y = targetY;
                moving = false;
            } else {
                x += (dx / dist) * speed * dt;
                y += (dy / dist) * speed * dt;
            }
        }
    }

    public void walkTo(int nq, int nr, float hexSize, float squash) {
        q = nq; r = nr;
        targetX = hexSize * (float) Math.sqrt(3) * (q + r / 2f);
        targetY = hexSize * 1.5f * r * squash;
        moving = true;
    }
}
