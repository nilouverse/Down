package com.down.game;

public class NilouZila extends Hero {

    public NilouZila() {
        name = "NILOU";
        voice = "nilou";
        keyPrefix = "nilou:";
        moveMax = 3;
        hoverLift = -15f;
        sheets = new SheetSpec[] {
            new SheetSpec("idle",  "sprites/idle.png",     2, 2, 4, false, true),
            new SheetSpec("glide", "sprites/glide.png",    2, 2, 2, true,  false),
            new SheetSpec("atk",   "sprites/attack_a.png", 2, 3, 2, true,  false),
            new SheetSpec("atk",   "sprites/attack_b.png", 2, 3, 2, true,  false),
        };
        idleSheets = new String[] { "idle" };
        glideSheet = "glide";
        glideLoop = new int[] { 1, 2 };
        glideFps = 6f;
        liftSeq = new int[] { 3, 1 };
        liftDur = new float[] { 0.1f, 0.1f };
        landSeq = new int[] { 2, 1, 3, 0 };
        landDur = new float[] { 0.06f, 0.06f, 0.05f, 0.05f };

        attacks = new Attack[] {
            new Attack(1, 15, 0, 0, new Step[] {
                new Step("atk", 0, 0.136f, EV_NONE),
                new Step("atk", 1, 0.136f, EV_NONE),
                new Step("atk", 2, 0.136f, EV_NONE),
                new Step("atk", 3, 0.136f, EV_NONE),
                new Step("atk", 4, 0.136f, EV_STRIKE),
                new Step("atk", 9, 0.136f, EV_NONE),
                new Step("atk", 5, 0.136f, EV_NONE),
            }),
            new Attack(3, 10, 20, 1, new Step[] {
                new Step("atk", 0, 0.15f, EV_NONE),
                new Step("atk", 1, 0.15f, EV_NONE),
                new Step("atk", 2, 0.15f, EV_NONE),
                new Step("atk", 9, 0.15f, EV_BOLT),
                new Step("atk", 5, 0.15f, EV_NONE),
            }),
            new Attack(2, 20, 50, 2, new Step[] {
                new Step("atk", 0,  0.139f, EV_NONE),
                new Step("atk", 1,  0.139f, EV_NONE),
                new Step("atk", 2,  0.139f, EV_NONE),
                new Step("atk", 8,  0.139f, EV_NONE),
                new Step("atk", 3,  0.139f, EV_NONE),
                new Step("atk", 4,  0.139f, EV_NONE),
                new Step("atk", 10, 0.139f, EV_NONE),
                new Step("atk", 11, 0.139f, EV_AOE),
                new Step("atk", 5,  0.139f, EV_NONE),
            }),
        };
    }

    @Override protected void decorate() {
        if (idleFrames.size() >= 4) {
            idleFrames.get(1).dx = -8f;
            idleFrames.get(2).dx = -8f;
        }
    }
}
