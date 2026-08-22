package com.down.game;

public class StoryActor {
    public String name, kind, type, tag, alias;
    public boolean hidden;
    public int q, r;
    public float x, y;
    public boolean walking;
    public float fromX, fromY, toX, toY;
    public float viaX, viaY, viaFrac;
    public float walkT, walkDuration;
    public float bobT;
    public float facing = 1f;
    public boolean glide;
    public float lift;
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
        this.lift = 0f;
    }

    public void moveToHex(int q, int r, float duration) {
        moveToHex(q, r, duration, false);
    }

    public void moveToHex(int q, int r, float duration, boolean glide) {
        this.fromX = this.x;
        this.fromY = this.y;
        this.toX = hexX(q, r);
        this.toY = hexY(q, r);
        this.viaFrac = 0f;
        this.q = q; this.r = r;
        this.walkDuration = Math.max(0.01f, duration);
        this.walkT = 0f;
        this.walking = true;
        this.glide = glide;
        this.lift = 0f;
        faceToward(this.toX);
    }

    public void moveToHexVia(int q, int r, int vq, int vr, float duration) {
        this.fromX = this.x;
        this.fromY = this.y;
        this.viaX = hexX(vq, vr);
        this.viaY = hexY(vq, vr);
        this.toX = hexX(q, r);
        this.toY = hexY(q, r);
        this.q = q; this.r = r;
        float d1 = dist(this.fromX, this.fromY, this.viaX, this.viaY);
        float d2 = dist(this.viaX, this.viaY, this.toX, this.toY);
        this.viaFrac = d1 / Math.max(1f, d1 + d2);
        this.walkDuration = Math.max(0.01f, duration);
        this.walkT = 0f;
        this.walking = true;
        this.glide = false;
        this.lift = 0f;
        faceToward(this.viaX);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void faceToward(float tx) {
        if (tx < x - 0.05f) facing = -1f;
        else if (tx > x + 0.05f) facing = 1f;
    }

    public void update(float dt) {
        bobT += dt;
        if (!walking) { lift = 0f; return; }
        walkT += dt;
        float t = Math.min(1f, walkT / walkDuration);
        if (viaFrac > 0f && t < viaFrac) {
            float u = t / viaFrac;
            float e = u * u * (3f - 2f * u);
            x = fromX + (viaX - fromX) * e;
            y = fromY + (viaY - fromY) * e;
            faceToward(viaX);
        } else if (viaFrac > 0f) {
            float u = (t - viaFrac) / Math.max(0.0001f, 1f - viaFrac);
            float e = u * u * (3f - 2f * u);
            x = viaX + (toX - viaX) * e;
            y = viaY + (toY - viaY) * e;
            faceToward(toX);
        } else {
            float e = t * t * (3f - 2f * t);
            x = fromX + (toX - fromX) * e;
            y = fromY + (toY - fromY) * e;
        }
        lift = glide ? (float) Math.sin(t * 3.14159f) * 150f : 0f;
        if (t >= 1f) { x = toX; y = toY; walking = false; lift = 0f; glide = false; viaFrac = 0f; }
    }

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
