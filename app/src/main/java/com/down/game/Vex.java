package com.down.game;

public class Vex extends Hero {

    public Vex() {
        name = "VEX";
        voice = "vex";
        keyPrefix = "vex:";
        moveMax = 4;
        hoverLift = -10f;
        atkSfx = new String[] { "slash", "poison", "slash" };
        sheets = new SheetSpec[] {
            new SheetSpec("idleA", "sprites/vex_idle_a.png", 2, 2, 4, false, true),
            new SheetSpec("idleB", "sprites/vex_idle_b.png", 2, 2, 4, false, true),
            new SheetSpec("glide", "sprites/vex_glide.png",  2, 2, 2, true,  false),
            new SheetSpec("atk",   "sprites/vex_attack.png", 2, 3, 2, true,  false),
        };
        idleSheets = new String[] { "idleA", "idleB" };
        glideSheet = "glide";
        glideLoop = new int[] { 0, 1, 2 };
        glideFps = 6f;
        liftSeq = new int[0];
        liftDur = new float[0];
        landSeq = new int[] { 3, 0 };
        landDur = new float[] { 0.12f, 0.12f };

        attacks = new Attack[] {
            // blink strike: crouch -> dash+shake -> vanish+slash on target -> reappear
            new Attack(1, 12, 0, 0, new Step[] {
                new Step("atk", 0, 0.16f, EV_NONE),
                new Step("glide", 1, 0.10f, EV_SHAKE),
                new Step("atk", HIDDEN, 0.22f, EV_STRIKE),
                new Step("atk", 4, 0.16f, EV_NONE),
                new Step("atk", 5, 0.16f, EV_NONE),
            }),
            // poisoned dart: single windup frame, target poisoned 5 x 4 enemy turns
            new Attack(4, 0, 20, 1, new Step[] {
                new Step("atk", 0, 0.30f, EV_POISON),
            }),
            // the dance: AoE radius 3, 30 dmg
            new Attack(3, 30, 60, 2, new Step[] {
                new Step("atk", 5, 0.14f, EV_NONE),
                new Step("atk", 1, 0.14f, EV_AOE),
                new Step("atk", 3, 0.14f, EV_NONE),
                new Step("atk", 2, 0.14f, EV_NONE),
                new Step("atk", 4, 0.14f, EV_NONE),
                new Step("atk", 1, 0.14f, EV_NONE),
                new Step("atk", 2, 0.14f, EV_NONE),
                new Step("atk", 0, 0.14f, EV_NONE),
            }),
        };
    }
}
