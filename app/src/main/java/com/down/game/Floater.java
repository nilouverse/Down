package com.down.game;

public class Floater {

    // 0 = standing (frame 1), 1 = rising (frame 2),
    // 2 = floating loop (frames 3-8), 3 = descending (frame 2)
    public int state = 0;
    public float t = 0;
    public boolean moving = false;

    public void update(float dt) {
        switch (state) {
            case 0: if (moving) { state = 1; t = 0; } break;
            case 1: t += dt;
                    if (!moving) { state = 3; t = 0; }
                    else if (t > 0.2f) { state = 2; t = 0; }
                    break;
            case 2: if (!moving) { state = 3; t = 0; } break;
            case 3: t += dt;
                    if (moving) { state = 1; t = 0; }
                    else if (t > 0.2f) { state = 0; t = 0; }
                    break;
        }
    }

    public boolean floating() { return state == 1 || state == 2; }

    // frame index inside an 8-frame sheet
    public int frame(float clock) {
        switch (state) {
            case 1: return 1;
            case 3: return 1;
            case 2: return 2 + ((int) (clock * 8)) % 6;
            default: return 0;
        }
    }
}
