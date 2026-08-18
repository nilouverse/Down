package com.down.game;

public class Floater {

    public int state = 0;
    public float t = 0;
    public boolean moving = false;
    public float visualY = 0;
    public float targetY = 0;

    public void update(float dt) {
        switch (state) {
            case 0: 
                targetY = 0;
                if (moving) { state = 1; t = 0; } 
                break;
            case 1: 
                t += dt;
                targetY = -15f;
                if (!moving) { state = 3; t = 0; }
                else if (t > 0.2f) { state = 2; t = 0; }
                break;
            case 2: 
                targetY = -12f + (float)Math.sin(t * 8f) * 3f;
                if (!moving) { state = 3; t = 0; } 
                break;
            case 3: 
                t += dt;
                targetY = 0;
                if (moving) { state = 1; t = 0; }
                else if (t > 0.2f) { state = 0; t = 0; }
                break;
        }
        float k = 1f - (float)Math.exp(-dt * 15f);
        visualY += (targetY - visualY) * k;
    }

    public boolean floating() { return state == 1 || state == 2; }

    public int frame(float clock) {
        switch (state) {
            case 1: return 1;
            case 3: return 1;
            case 2: return 2 + ((int) (clock * 8)) % 6;
            default: return 0;
        }
    }
}
