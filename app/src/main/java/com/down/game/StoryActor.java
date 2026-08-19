package com.down.game;

public class StoryActor {
    public String name, kind;
    public boolean hidden;
    public int q, r;
    public float x, y;
    public boolean walking;
    public float fromX, fromY, toX, toY;
    public float walkT, walkDuration;
    public float bobT;
    public float facing = 1f;
    public Frame[] idleFrames;

    public StoryActor(String name, String kind, float x, float y, boolean hidden) {
        this.name = name; this.kind = kind;
        this.x = x; this.y = y;
        this.hidden = hidden;
        this.walking = false;
    }

    public boolean isEnemy() {
        return kind.equals("enemy");
    }

    public void setHex(int q, int r) {
        this.q = q; this.r = r;
        this.x = SceneMap.hexX(q, r);
        this.y = SceneMap.hexY(q, r);
        this.walking = false;
    }

    public void moveToHex(int q, int r, float duration) {
        this.fromX = this.x;
        this.fromY = this.y;
        this.toX = SceneMap.hexX(q, r);
        this.toY = SceneMap.hexY(q, r);
        this.q = q; this.r = r;
        this.walkDuration = Math.max(0.01f, duration);
        this.walkT = 0f;
        this.walking = true;
    }

    public void update(float dt) {
        bobT += dt;
        if (!walking) return;
        walkT += dt;
        float t = Math.min(1f, walkT / walkDuration);
        float ease = t * t * (3f - 2f * t);
        x = fromX + (toX - fromX) * ease;
        y = fromY + (toY - fromY) * ease;
        if (t >= 1f) { x = toX; y = toY; walking = false; }
    }
}
