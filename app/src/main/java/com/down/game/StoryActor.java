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

    // --- cinematic movement v2 ---
    private static final int MAX_WP = 10;
    private static final float FACING_DUR = 0.16f;

    private final float[] wpX = new float[MAX_WP], wpY = new float[MAX_WP];
    private final float[] segLen = new float[MAX_WP];
    private final float[] segT0 = new float[MAX_WP];
    private int wpN = 0;
    private float pathLen = 0f;
    private float delayT = 0f;
    private float facingCur = 1f, facingFrom = 1f, facingT = 1f;
    public float animPhase = 0f;
    public boolean arrived = false;
    public boolean exitPending = false;

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
    public boolean isWalking() { return walking || delayT > 0f; }
    public boolean movingNow() { return walking && delayT <= 0f; }
    public float facingDisplay() { return facingCur; }

    public void snapFacing(float f) {
        facing = f;
        facingCur = f;
        facingFrom = f;
        facingT = 1f;
    }

    public void turnTo(float f) {
        if (f == facing && facingT >= 1f) return;
        facing = f;
        facingFrom = facingCur;
        facingT = 0f;
    }

    public void setHex(int q, int r) {
        this.q = q; this.r = r;
        this.x = hexX(q, r);
        this.y = hexY(q, r);
        this.walking = false;
        this.lift = 0f;
        this.glide = false;
        this.delayT = 0f;
        this.wpN = 0;
        this.pathLen = 0f;
        this.arrived = false;
        this.exitPending = false;
        this.viaFrac = 0f;
    }

    public void moveToHex(int q, int r, float duration) {
        moveToHex(q, r, duration, false);
    }

    public void moveToHex(int q, int r, float duration, boolean glide) {
        float[] xs = { this.x, hexX(q, r) };
        float[] ys = { this.y, hexY(q, r) };
        startPath(xs, ys, 2, duration, 0f, glide);
    }

    public void moveToHexVia(int q, int r, int vq, int vr, float duration) {
        float[] xs = { this.x, hexX(vq, vr), hexX(q, r) };
        float[] ys = { this.y, hexY(vq, vr), hexY(q, r) };
        startPath(xs, ys, 3, duration, 0f, false);
    }

    public void startPath(float[] xs, float[] ys, int n, float duration, float delaySec, boolean glideMove) {
        if (xs == null || ys == null || n < 2) return;
        if (n > MAX_WP) n = MAX_WP;
        for (int i = 0; i < n; i++) { wpX[i] = xs[i]; wpY[i] = ys[i]; }
        wpN = n;
        pathLen = 0f;
        for (int i = 0; i < n - 1; i++) {
            float d = dist(wpX[i], wpY[i], wpX[i + 1], wpY[i + 1]);
            segLen[i] = d;
            pathLen += d;
        }
        fromX = xs[0]; fromY = ys[0];
        toX = xs[n - 1]; toY = ys[n - 1];
        viaX = n >= 3 ? xs[1] : xs[n - 1];
        viaY = n >= 3 ? ys[1] : ys[n - 1];
        if (pathLen < 0.001f) {
            walking = false; delayT = 0f; glide = false; lift = 0f;
            if (exitPending) { hidden = true; exitPending = false; }
            return;
        }
        segT0[0] = 0f;
        for (int i = 0; i < n - 1; i++) segT0[i + 1] = segT0[i] + segLen[i] / pathLen;
        segT0[n - 1] = 1f;
        viaFrac = segT0[1];
        walkDuration = Math.max(0.01f, duration);
        walkT = 0f;
        walking = true;
        glide = glideMove;
        lift = 0f;
        delayT = Math.max(0f, delaySec);
        arrived = false;
        faceToward(wpX[1]);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void faceToward(float tx) {
        float nf;
        if (tx < x - 0.05f) nf = -1f;
        else if (tx > x + 0.05f) nf = 1f;
        else return;
        if (nf != facing) {
            facing = nf;
            facingFrom = facingCur;
            facingT = 0f;
        }
    }

    public void update(float dt) {
        bobT += dt;
        if (facingT < 1f) {
            facingT = Math.min(1f, facingT + dt / FACING_DUR);
            float u = facingT;
            float e = u * u * (3f - 2f * u);
            facingCur = facingFrom + (facing - facingFrom) * e;
        } else {
            facingCur = facing;
        }
        if (delayT > 0f) { delayT -= dt; return; }
        if (!walking) { lift = 0f; return; }
        walkT += dt;
        float t = Math.min(1f, walkT / walkDuration);
        float speed = pathLen / walkDuration;
        float cyc = speed / (HEX * 1.05f);
        if (cyc < 1.1f) cyc = 1.1f;
        if (cyc > 3.4f) cyc = 3.4f;
        animPhase += dt * cyc;
        int seg = 0;
        while (seg < wpN - 2 && t >= segT0[seg + 1]) seg++;
        float span = segT0[seg + 1] - segT0[seg];
        float u = span > 0.0001f ? (t - segT0[seg]) / span : 1f;
        if (u < 0f) u = 0f;
        if (u > 1f) u = 1f;
        float e = u * u * (3f - 2f * u);
        x = wpX[seg] + (wpX[seg + 1] - wpX[seg]) * e;
        y = wpY[seg] + (wpY[seg + 1] - wpY[seg]) * e;
        faceToward(wpX[seg + 1]);
        lift = glide ? (float) Math.sin(t * 3.14159f) * 150f : 0f;
        if (t >= 1f) {
            x = wpX[wpN - 1]; y = wpY[wpN - 1];
            walking = false; lift = 0f; glide = false;
            arrived = true;
            if (exitPending) { hidden = true; exitPending = false; }
        }
    }

    private static float hexX(int q, int r) { return HEX * SQRT3 * (q + r * 0.5f); }
    private static float hexY(int q, int r) { return HEX * 1.5f * r * SQUASH; }
}
