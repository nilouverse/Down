package com.down.game;

public class StoryActor {
    public String name, kind, type, tag, alias;
    public boolean hidden;
    public int q, r;
    public float x, y;
    public boolean walking;
    public float fromX, fromY, toX, toY;
    public float walkT, walkDuration;
    public float bobT;
    public float facing = 1f;
    public Frame[] idleFrames;
    public Frame[] glideFrames;

    private static final float HEX = 96f, SQUASH = 0.6f, SQRT3 = 1.7320508f;

    public StoryActor(String name, String kind, String type, String tag, float x, float y, boolean hidden) {
        this.name = name;
        this.kind = kind;
        this.type = type;
        this.tag = tag;
        this.x = x;
        this.y = y;
        this.hidden = hidden;
        this.walking = false;
    }

    public boolean isEnemy() { return "enemy".equals(kind) || "beast".equals(kind); }
    public boolean isWalking() { return walking; }

    public void setHex(int q, int r) {
        this.q = q; this.r = r;
        this.x = hexX(q, r);
        this.y = hexY(q, r);
        this.walking = false;
    }

    public void moveToHex(int q, int r, float duration) {
        this.fromX = this.x;
        this.fromY = this.y;
        this.toX = hexX(q, r);
        this.toY = hexY(q, r);
        this.q = q; this.r = r;
        this.walkDuration = Math.max(0.01f, duration);
        this.walkT = 0f;
        this.walking = true;
        // align facing with the move direction, same as gameplay
        if (toX < fromX - 0.05f) facing = -1f;
        else if (toX > fromX + 0.05f) facing = 1f;
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

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
