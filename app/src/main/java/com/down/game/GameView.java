package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class GameView extends SurfaceView implements Runnable, SurfaceHolder.Callback, Story.Host {

    private static final int STATE_MENU = 0, STATE_GAME = 1, STATE_STORY = 2,
            STATE_SELECT = 3, STATE_CHAPTER = 4;
    private static final int PH_PLAYER = 0, PH_ENEMY = 1;

    private static final float SQUASH = 0.6f;
    private static final float HEX = 96f;
    private static final float TILE = 192f;
    private static final float TH = TILE * SQUASH;
    private static final int MOVE_HEX = 3;
    private static final float PLAYER_H = 200f, ENEMY_H = 200f;
    private static final float FOOT_DROP = 40f;
    private static final long FRAME_NS = 16666667L;
    private static final float ZOOM_MIN = 0.9f, ZOOM_MAX = 2.0f;
    private static final int GROUND_COL = 0xFF0a0608;
    private static final float[] FOG_UH = { 0, -0.6f, 0.866f, -0.3f, 0.866f, 0.3f,
            0, 0.6f, -0.866f, 0.3f, -0.866f, -0.3f };

    private static final int C_INK = 0xFF0a0608;
    private static final int C_BLOOD = 0xFFb3102a;
    private static final int C_BRIGHT = 0xFFff2747;
    private static final int C_EMBER = 0xFFff7a1a;
    private static final int C_BONE = 0xFFefe6dd;
    private static final int C_BONE_DIM = 0xFFb7a6ab;
    private static final int C_CYAN = 0xFF34e3d6;
    private static final int C_VIOLET = 0xFFb07cff;
    private static final int C_MAGENTA = 0xFFff2d7e;
    private static final long FRAME_NS_MENU = 33333333L;
    private static final String[] LOAD_LINES = {
            "tuning the strings…", "warming the tubes…", "dropping the needle…" };

    // precomputed tinted hex bitmaps (B2)
    private static final int[] HEX_PAL = { 0xAAefe6dd, 0xFFefe6dd, 0x14efe6dd, 0x22efe6dd, 0xFF34e3d6 };
    private Bitmap[] hexTinted;

    // cached move-fan / attack-range hex lists (B3)
    private int fanQ, fanR;
    private int[] fanQs = new int[160];
    private int[] fanRs = new int[160];
    private int fanN = 0;
    private int fanMoveMax = -1;
    private boolean fanDirty = true;
    private int[] fan2Qs = new int[160];
    private int[] fan2Rs = new int[160];
    private int fan2N = 0;
    private final HashMap<Long, Integer> reachDist = new HashMap<>();
    private final HashMap<Long, Long> reachParent = new HashMap<>();
    private int atkRangeQ, atkRangeR, atkRangeR2;
    private int atkRangeKind;
    private int[] atkQs = new int[64];
    private int[] atkRs = new int[64];
    private int atkN = 0;
    private boolean atkDirty = true;

    // parallax treeline strip (E1) + heartbeat tint (E2)
    private Bitmap treeStrip;
    private float hbPulse = 0f;

    // camera zoom punch (D4)
    private float zoomPunch = 0f;

    private String lastSceneName = "";
    private final char[] posBuf = new char[16];
    private int fadeState = 0;   // 0 none, 1 out, 2 black-hold
    private float fadeT = 0f;
    private int fadeDur = 1000;
    private static final float[] HEXU = { 0, -0.6f, 0.866f, -0.3f, 0.866f, 0.3f,
            0, 0.6f, -0.866f, 0.3f, -0.866f, -0.3f };
    public SceneMap map;
    public StoryActors actors;
    private float camLookX, camLookY, camLookT;

    // cinematic director state (script-driven camera / walk / fx)
    private int camMode = 0; // 0 default follow, 1 follow actor, 2 pan-hold
    private String camFollowName = null;
    private float panFromX, panFromY, panToX, panToY, panT, panDur;
    private float pushT = -1f, pushDur = 1f;
    private boolean swActive;
    private boolean swGlide;
    private float swLift;
    private float swFromX, swFromY, swToX, swToY, swT, swDur;
    private String fxActorName = null, fxActorKind = null;
    private float fxActorT = 0f;

    private static final int[][] ATK_SEQ = {
            { 0, 1, 2, 3, 4, 9, 5 },
            { 0, 1, 2, 9, 5 },
            { 0, 1, 2, 8, 3, 4, 10, 11, 5 } };
    private static final float[] ATK_DUR = { 0.95f, 0.75f, 1.25f };
    private static final float[] ATK_HIT = { 0.60f, 0.55f, 0.80f };
    private static final int[] ATK_RANGE = { 1, 3, 2 };
    private static final int[] ATK_DMG = { 15, 10, 20 };
    private static final int[] ATK_MANA = { 0, 20, 50 };
    private static final int[][] NEIGH6 = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, -1 }, { -1, 1 } };

    private Thread loop;
    private volatile boolean running;

    private int state = STATE_MENU;
    private Story story;
    private boolean storyFight;
    private boolean storyMode;
    private boolean storyTest;
    private final RectF chapterBtn = new RectF();
    private boolean camSnap;
    private int menuPress;
    private final RectF menuBtnTest = new RectF();
    private final RectF menuBtnStory = new RectF();

    private final ArrayList<Player> party = new ArrayList<>();
    private Player player = new Player();

    private float camX, camY;
    private float zoom = 1.25f;
    private float zoomTarget = 1.25f;
    private boolean scriptZoom;
    private boolean exploring;
    private float exploreT;
    private float downX = -9999, downY, lastPX, lastPY;
    private boolean moved, panning;
    private boolean pinching;
    private float pinchDist0, pinchZoom0;
    private float pinchCX, pinchCY;
    private float velX, velY, flingX, flingY;
    private long lastMoveT;

    private static class Slash { float x, y, rot, t; boolean active; }
    private final Slash[] slashPool = new Slash[24];

    private final ArrayList<Frame> eIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eGlowFr = new ArrayList<>();
    private final ArrayList<Frame> eAtkFr = new ArrayList<>();
    private final ArrayList<Frame> eHeavyIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eHeavyGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eHeavyAtkFr = new ArrayList<>();
    private final ArrayList<Frame> eBeastIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eBeastGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eBeastAtkFr = new ArrayList<>();
    private final ArrayList<Frame> eSoldierIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eSoldierGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eSabrinaIdleFr = new ArrayList<>();
    private final ArrayList<Frame> eSabrinaGlideFr = new ArrayList<>();
    private final ArrayList<Frame> eRoomPropsFr = new ArrayList<>();
    private List<Bitmap> propsGate;
    private final HashSet<Enemy> biteFired = new HashSet<>();
    private final float[] biteX = new float[4], biteY = new float[4], biteT0 = new float[4];
    private final boolean[] biteOn = new boolean[4];
    private int biteSlot = 0;
    private Frame[] arrEnIdle, arrEnGlide, arrInfIdle, arrInfGlide,
            arrBeastIdle, arrBeastGlide, arrSoldierIdle, arrSoldierGlide,
            arrSabrinaIdle, arrSabrinaGlide, arrRoomProps;
    private final HashMap<Enemy, float[]> leapOff = new HashMap<>();
    private final HashMap<String, Frame[]> heroSheets = new HashMap<>();
    private final HashMap<String, Frame[]> heroGlide = new HashMap<>();
    private final HashMap<Player, Integer> bleedTurns = new HashMap<>();
    private final HashMap<Player, Integer> bleedDmg = new HashMap<>();
    private static final int BEAST_B1_DMG = 14, BEAST_B2_DMG = 30,
            BEAST_B1_RANGE = 5, BEAST_BLEED_PCT = 20, BEAST_MANA_REGEN = 30;

    private List<Bitmap> props, props2, propsCity;
    private List<Bitmap> propsAF, propsBF, propsCF;

    private float runeX, runeY, runeT = 99;

    private static class Particle { float x, y, vx, vy, t, life, grav = 400; int col; boolean active; }
    private final Particle[] particlePool = new Particle[200];
    private static class Puff { float x, y, t; boolean active; }
    private final Puff[] puffPool = new Puff[50];
    private float puffTimer;

    private static class Blast { float x, y, t; boolean active; }
    private final Blast[] blastPool = new Blast[20];

    private Bitmap overlay, menuBmp, shadowBmp;
    private static class Ember { float x, y, s; }
    private final ArrayList<Ember> embers = new ArrayList<>();

    private boolean hexesShown = false;
    private int actionsLeft = 2;
    private int attackRangeShown = 0;
    private int mana = 100;
    private String voice = "nilou";
    private Enemy targetEnemy = null;
    private static class Bolt { float x, y, x0, y0, tx, ty, t; Enemy tgt; int dmg; boolean active; }
    private final Bolt[] boltPool = new Bolt[20];

    private int phase = PH_PLAYER;
    private float phaseT = 0;

    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final HashMap<Long, Integer> flow = new HashMap<>();
    private final HashSet<Long> reserved = new HashSet<>();
    private float hurtT = 0, deadT = 0;
    private static class Dmg { float x, y, t; int val; int col; String txt; boolean active; }
    private final Dmg[] dmgPool = new Dmg[30];

    private static class D { float y; int kind; Enemy en; Player pl; Bitmap pr; float ax, ay, s; Object obj; }
    private final ArrayList<D> drawList = new ArrayList<>();
    private final ArrayList<D> dPool = new ArrayList<>();
    private static final Comparator<D> BY_Y = new Comparator<D>() {
        public int compare(D a, D b) { return a.y < b.y ? -1 : (a.y > b.y ? 1 : 0); }
    };

    private static final float[] FW_A = new float[2];
    private static final int[] IH_A = new int[2];
    private static final int[] IH_B = new int[2];
    private static final int[] IH_C = new int[2];
    private static final float[] TW_F = new float[2];
    private static final int[] TW_A = new int[2];
    private static final int[] TW_B = new int[2];
    private static final int[] TW_C = new int[2];
    private static final float[] HO_F = new float[2];
    private static final int[] HO_A = new int[2];

    private final Paint paint = new Paint();
    private final Paint tintPaint = new Paint();
    private final Path hexPath = new Path();
    private final RectF rf = new RectF();
    private final Rect frameSrc = new Rect();
    private final ColorMatrixColorFilter brightFlash = new ColorMatrixColorFilter(new float[] {
            1.16f, 0, 0, 0, 90,
            0, 0.6f, 0, 0, 0,
            0, 0, 0.6f, 0, 0,
            0, 0, 0, 1, 0 });
    private PorterDuffColorFilter blastFilter;
    private int W, H;

    private float hitstopT = 0;
    private float shakeT = 0, shakeX = 0, shakeY = 0;
    private Bitmap hexBmp, blastBmp;
    private final Sound sound = new Sound();

    private volatile boolean surfaceAlive;
    private long frameCostEma = 8000000L;
    private int quality = 1;
    private final Runnable hapticRun = new Runnable() {
        public void run() {
            hapticTiered(1);
        }
    };

    private final Hero[] roster = new Hero[] { new NilouZila(), new Vex() };
    private Bitmap menuBg, keyBmp, coinBmp;
    private final RectF selBtn0 = new RectF(), selBtn1 = new RectF();
    private float dockSlide = 0;
    private final RectF dockPanel = new RectF();
    private final RectF dockEnd = new RectF();
    private final RectF[] dockAtk = new RectF[] {
            new RectF(), new RectF(), new RectF() };
    private Typeface fLogo, fBody, fSerif;
    private volatile boolean assetsReady;
    private float loadT = 0;
    private Bitmap gameOverlay, splatterBmp;
    private HashMap<String, List<Frame>> loadedFrames;
    private static class Decal { float x, y, rot, s, flip; boolean active; }
    private final Decal[] decalPool = new Decal[8];
    private static class AssetBundle {
        HashMap<String, List<Frame>> frames;
        ArrayList<Frame> eIdle, eGlide, eAtk;
        ArrayList<Frame> eHeavyIdle, eHeavyGlide, eHeavyAtk;
        ArrayList<Frame> eBeastIdle, eBeastGlide, eBeastAtk;
        ArrayList<Frame> eSoldierIdle, eSoldierGlide;
        ArrayList<Frame> eSabrinaIdle, eSabrinaGlide;
        ArrayList<Frame> eRoomProps;
        List<Bitmap> gate;
        List<Bitmap> props, props2, propsCity;
        List<Bitmap> propsAF, propsBF, propsCF;
        Bitmap menuBg, keyBmp, coinBmp;
    }
    private volatile AssetBundle pending;
    private final Path btnPath = new Path();
    private final Path ekgPath = new Path();

    public GameView(Context ctx) {
        super(ctx);
        getHolder().addCallback(this);
        sound.init(ctx);
        // A3: fonts + splatter load off UI thread

        Thread loader = new Thread(new Runnable() { public void run() {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Context c = getContext();
            loadFonts(c);
            try {
                splatterBmp = BitmapFactory.decodeStream(
                        c.getAssets().open("art/blood.webp"));
            } catch (Exception e) { splatterBmp = null; }
            AssetBundle b = new AssetBundle();
            b.frames = new HashMap<>();
            for (Hero h : roster) {
                for (Hero.SheetSpec s : h.sheets) {
                    List<Frame> built = Sprites.buildFrames(Sprites.cutSheet(
                            c, s.path, s.rows, s.cols, s.margin), s.vCrop, s.cCenter);
                    String key = h.keyPrefix + s.id;
                    List<Frame> l = b.frames.get(key);
                    if (l == null) { l = new ArrayList<>(); b.frames.put(key, l); }
                    l.addAll(built);
                }
            }
            b.eIdle = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_idle.png", 2, 2, 4), false, true));
            b.eGlide = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_glide.png", 2, 2, 2), true, false));
            b.eAtk = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_attack.png", 2, 3, 2), false, false));
            b.eHeavyIdle = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_heavy_idle.png", 2, 2, 4), false, true));
            b.eHeavyGlide = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_heavy_glide.png", 2, 2, 2), true, false));
            b.eHeavyAtk = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_heavy_attack.png", 3, 4, 2), false, false));
            b.eBeastIdle = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_beast_idle_a.png", 2, 2, 4), false, true));
            b.eBeastIdle.addAll(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_beast_idle_b.png", 2, 2, 4), false, true));
            b.eBeastGlide = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_beast_glide_a.png", 2, 2, 2), true, false));
            b.eBeastGlide.addAll(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_beast_glide_b.png", 2, 2, 2), true, false));
            b.eBeastAtk = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/enemy_beast_attack.png", 2, 3, 2), false, false));
            for (Frame f : b.eBeastAtk) f.ref /= 1.2f;   // K1: attack art reads small — draw bigger
            b.eSoldierIdle = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/soldier_idle.png", 2, 2, 4), false, true));
            b.eSoldierGlide = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/soldier_glide.png", 2, 2, 2), true, false));
            b.eSabrinaIdle = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/sabrina_idle.png", 2, 2, 4), false, true));
            b.eSabrinaGlide = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "sprites/sabrina_glide.png", 2, 2, 2), true, false));
            b.eRoomProps = new ArrayList<>(Sprites.buildFrames(
                    Sprites.cutSheet(c, "map/props_room.png", 2, 2, 2), false, false));
            b.props  = Sprites.trimBottom(
                    Sprites.cutSheet(c, "map/props_a.png", 4, 4, 4), 0.9f);
            b.props2 = Sprites.trimBottom(
                    Sprites.cutSheet(c, "map/props_b.png", 4, 4, 4), 0.9f);
            b.propsCity = Sprites.trimBottom(
                    Sprites.cutSheet(c, "map/props_city.png", 2, 4, 4), 0.9f);
            b.propsAF = Sprites.cutSheet(c, "map/props_a.png", 4, 4, 2);
            b.propsBF = Sprites.cutSheet(c, "map/props_b.png", 4, 4, 2);
            b.propsCF = Sprites.cutSheet(c, "map/props_city.png", 2, 4, 2);
            scrubFringe(b.props); scrubFringe(b.props2); scrubFringe(b.propsCity);
            scrubFringe(b.propsAF); scrubFringe(b.propsBF); scrubFringe(b.propsCF);
            b.gate = Sprites.tightCrop(Sprites.cutSheet(c, "map/props_gate.png", 2, 4, 2));
            scrubFringe(b.gate);
            scrubFrames(b.eBeastIdle); scrubFrames(b.eBeastGlide); scrubFrames(b.eBeastAtk);
            scrubFrames(b.eSoldierIdle); scrubFrames(b.eSoldierGlide);
            b.menuBg = decodeSampled(c, "art/hero.webp", 1024);
            b.keyBmp = decodeRaw(c, "art/button.webp");
            b.coinBmp = decodeRaw(c, "art/coin.webp");
            pending = b;
            assetsReady = true;
        } }, "NV-assetload");
        loader.start();

        paint.setFilterBitmap(true);
        tintPaint.setFilterBitmap(true);
        tintPaint.setColorFilter(brightFlash);

        shadowBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shadowBmp);
        Paint shp = new Paint();
        shp.setShader(new RadialGradient(64, 64, 62, 0xB4000000, 0x00000000, Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, 128, 128, shp);

        worldToHex(640, 640, IH_A);
        hexToWorld(IH_A[0], IH_A[1], FW_A);
        player.hero = roster[0];
        player.x = FW_A[0]; player.y = FW_A[1];
        player.targetX = FW_A[0]; player.targetY = FW_A[1];

        for (int i = 0; i < particlePool.length; i++) particlePool[i] = new Particle();
        for (int i = 0; i < decalPool.length; i++) decalPool[i] = new Decal();
        for (int i = 0; i < slashPool.length; i++) slashPool[i] = new Slash();
        for (int i = 0; i < puffPool.length; i++) puffPool[i] = new Puff();
        for (int i = 0; i < blastPool.length; i++) blastPool[i] = new Blast();
        for (int i = 0; i < boltPool.length; i++) boltPool[i] = new Bolt();
        for (int i = 0; i < dmgPool.length; i++) dmgPool[i] = new Dmg();

        hexBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas hc = new Canvas(hexBmp);
        Paint hp = new Paint(); hp.setColor(0xFFFFFFFF); hp.setAntiAlias(true);
        Path hPath = new Path();
        for (int i = 0; i < 6; i++) {
            float a = (float) Math.toRadians(60 * i - 30);
            float x = 64 + 60 * (float) Math.cos(a);
            float y = 64 + 60 * SQUASH * (float) Math.sin(a);
            if (i == 0) hPath.moveTo(x, y); else hPath.lineTo(x, y);
        }
        hPath.close(); hc.drawPath(hPath, hp);

        blastBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(blastBmp);
        Paint bp = new Paint(); bp.setColor(0xFFFFFFFF); bp.setAntiAlias(true);
        bc.drawCircle(64, 64, 60, bp);
    }

    public void start() {
        if (running) return;
        sound.init(getContext());
        sound.resumeAll();
        running = true;
        loop = new Thread(this);
        loop.start();
    }

    public void stop() {
        running = false;
        try { if (loop != null) loop.join(); } catch (Exception e) {}
        sound.setFootsteps(false);
        sound.stopAll();
    }

    public void destroy() {
        stop();
        sound.destroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceAlive = true; }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { surfaceAlive = true; }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceAlive = false; }

    private void loadFonts(Context c) {
        try { fLogo = Typeface.createFromAsset(c.getAssets(),
                "fonts/MetalMania-Regular.ttf"); } catch (Exception e) { fLogo = Typeface.DEFAULT; }
        try { fBody = Typeface.createFromAsset(c.getAssets(),
                "fonts/SpaceGrotesk-Bold.ttf"); } catch (Exception e) { fBody = Typeface.DEFAULT_BOLD; }
        try { fSerif = Typeface.createFromAsset(c.getAssets(),
                "fonts/InstrumentSerif-Italic.ttf"); } catch (Exception e) { fSerif = Typeface.DEFAULT; }
    }

    private static Frame wrapFrame(Bitmap b) {
        Frame f = new Frame();
        f.bmp = b;
        f.top = 0; f.left = 0;
        f.cw = b.getWidth(); f.ch = b.getHeight();
        f.rgt = f.cw; f.ww = f.cw;
        f.ref = f.ch;
        return f;
    }

    private void applyAssets() {
        AssetBundle b = pending;
        pending = null;
        loadedFrames = b.frames;
        for (Hero h : roster) {
            h.bind(b.frames);
            if (!h.idleFrames.isEmpty())
                heroSheets.put(h.voice, h.idleFrames.toArray(new Frame[0]));
            List<Frame> gl = b.frames.get(h.keyPrefix + h.glideSheet);
            if (gl != null && !gl.isEmpty())
                heroGlide.put(h.voice, gl.toArray(new Frame[0]));
        }
        eIdleFr.addAll(b.eIdle); eGlideFr.addAll(b.eGlide); eAtkFr.addAll(b.eAtk);
        eHeavyIdleFr.addAll(b.eHeavyIdle);
        eHeavyGlideFr.addAll(b.eHeavyGlide);
        eHeavyAtkFr.addAll(b.eHeavyAtk);
        eBeastIdleFr.addAll(b.eBeastIdle);
        eBeastGlideFr.addAll(b.eBeastGlide);
        eBeastAtkFr.addAll(b.eBeastAtk);
        eSoldierIdleFr.addAll(b.eSoldierIdle);
        eSoldierGlideFr.addAll(b.eSoldierGlide);
        eSabrinaIdleFr.addAll(b.eSabrinaIdle);
        eSabrinaGlideFr.addAll(b.eSabrinaGlide);
        eRoomPropsFr.addAll(b.eRoomProps);
        props = b.props; props2 = b.props2; propsCity = b.propsCity;
        propsAF = b.propsAF; propsBF = b.propsBF; propsCF = b.propsCF;
        propsGate = b.gate;
        arrEnIdle = eIdleFr.toArray(new Frame[0]);
        arrEnGlide = eGlideFr.toArray(new Frame[0]);
        arrInfIdle = eHeavyIdleFr.toArray(new Frame[0]);
        arrInfGlide = eHeavyGlideFr.toArray(new Frame[0]);
        arrBeastIdle = eBeastIdleFr.toArray(new Frame[0]);
        arrBeastGlide = eBeastGlideFr.toArray(new Frame[0]);
        arrSoldierIdle = eSoldierIdleFr.toArray(new Frame[0]);
        arrSoldierGlide = eSoldierGlideFr.toArray(new Frame[0]);
        arrSabrinaIdle = eSabrinaIdleFr.toArray(new Frame[0]);
        arrSabrinaGlide = eSabrinaGlideFr.toArray(new Frame[0]);
        arrRoomProps = eRoomPropsFr.toArray(new Frame[0]);
        menuBg = b.menuBg; keyBmp = b.keyBmp; coinBmp = b.coinBmp;

        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();

        // B2: precompute palette of tinted hex bitmaps once
        if (hexTinted == null && hexBmp != null) {
            hexTinted = new Bitmap[HEX_PAL.length];
            for (int i = 0; i < HEX_PAL.length; i++) {
                hexTinted[i] = Sprites.tinted(hexBmp, HEX_PAL[i]);
            }
        }
        fanDirty = true;
        atkDirty = true;
    }

    private static Bitmap decodeSampled(Context c, String path, int max) {
        try {
            java.io.InputStream in = c.getAssets().open(path);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int scale = 1;
            while (Math.max(o.outWidth, o.outHeight) / scale > max) scale *= 2;
            in = c.getAssets().open(path);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return b;
        } catch (Exception e) { return null; }
    }

    private static Bitmap decodeRaw(Context c, String path) {
        try {
            java.io.InputStream in = c.getAssets().open(path);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) { return null; }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        if (W <= 0 || H <= 0) return;

        int w2 = Math.max(2, W / 2), h2 = Math.max(2, H / 2);
        Paint p = new Paint();

        overlay = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(overlay);
        p.setShader(new LinearGradient(0, 0, 0, h2,
                new int[] { 0x26140a1c, 0x00000000, 0x00000000, 0x1C10061a },
                new float[] { 0f, 0.22f, 0.72f, 1f }, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 * 0.18f, -h2 * 0.25f, h2 * 1.15f,
                0x20ffffff, 0x00000000, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 / 2f, Math.max(w2, h2) * 0.75f,
                0x00000000, 0x78000000, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, w2, h2, p);

        menuBmp = Bitmap.createBitmap(w2, h2, Bitmap.Config.RGB_565);
        Canvas mc = new Canvas(menuBmp);
        mc.drawColor(C_INK);
        p.setShader(new RadialGradient(w2 / 2f, h2 * 0.32f, Math.max(w2, h2) * 0.62f,
                0x34ff2d7e, 0x00000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 * 1.2f, Math.max(w2, h2) * 0.85f,
                0x2ab07cff, 0x00000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);
        p.setShader(new RadialGradient(w2 / 2f, h2 / 2f, Math.max(w2, h2) * 0.72f,
                0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, w2, h2, p);

        gameOverlay = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888);
        Canvas goc = new Canvas(gameOverlay);
        goc.drawColor(0);
        p.setShader(new RadialGradient(w2 / 2f, h2 / 2f, Math.max(w2, h2) * 0.75f,
                0x00000000, 0x6A000000, Shader.TileMode.CLAMP));
        goc.drawRect(0, 0, w2, h2, p);
        p.setShader(null);
        java.util.Random nr = new java.util.Random(1234);
        for (int i = 0; i < 900; i++) {
            p.setColor((nr.nextInt(10) + 2) << 24 | 0xFFFFFF);
            goc.drawPoint(nr.nextInt(w2), nr.nextInt(h2), p);
        }

        if (embers.isEmpty()) {
            for (int i = 0; i < 40; i++) {
                Ember em = new Ember();
                em.x = (float) (Math.random() * W);
                em.y = (float) (Math.random() * H);
                em.s = 20 + (float) (Math.random() * 60);
                embers.add(em);
            }
        }

        // E1: procedural parallax treeline strip
        if (treeStrip == null || treeStrip.getWidth() != W) {
            if (treeStrip != null && !treeStrip.isRecycled()) treeStrip.recycle();
            int th = Math.max(40, H / 6);
            treeStrip = Bitmap.createBitmap(W, th, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(treeStrip);
            Paint tp = new Paint();
            tp.setColor(0xFF040209);
            Path tpPath = new Path();
            tpPath.moveTo(0, th);
            float px = 0;
            java.util.Random pr = new java.util.Random(42);
            while (px < W) {
                float peakH = 8 + pr.nextInt(th - 12);
                tpPath.lineTo(px, th - peakH);
                px += 6 + pr.nextInt(18);
                tpPath.lineTo(px, th - 2 - pr.nextInt(8));
                px += 4 + pr.nextInt(12);
            }
            tpPath.lineTo(W, th);
            tpPath.close();
            tc.drawPath(tpPath, tp);
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DISPLAY);
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.1f) dt = 0.1f;
            long t0 = System.nanoTime();
            update(dt);
            draw();
            long cost = System.nanoTime() - t0;
            frameCostEma += (cost - frameCostEma) * 0.05f;
            if (frameCostEma > 12000000L && quality > 0) quality--;
            else if (frameCostEma < 7000000L && quality < 1) quality = 1;
            long period = (state == STATE_MENU || state == STATE_SELECT || state == STATE_CHAPTER)
                    ? FRAME_NS_MENU : FRAME_NS;
            long rem = period - (System.nanoTime() - now);
            if (rem > 0) {
                long sleepNs = rem - 2000000L;
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1000000,
                            (int) (sleepNs % 1000000)); } catch (Exception e) {}
                }
                while (System.nanoTime() - now < period) {
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                }
            }
        }
    }

    private static void hexToWorld(int q, int r, float[] out) {
        out[0] = HEX * (float) Math.sqrt(3) * (q + r / 2f);
        out[1] = HEX * 1.5f * r * SQUASH;
    }

    private static void worldToHex(float x, float y, int[] out) {
        float hy = y / SQUASH;
        float qf = ((float) Math.sqrt(3) / 3f * x - 1f / 3f * hy) / HEX;
        float rf2 = (2f / 3f * hy) / HEX;
        float sf = -qf - rf2;
        int rq = Math.round(qf), rr = Math.round(rf2), rs = Math.round(sf);
        float dq = Math.abs(rq - qf), dr = Math.abs(rr - rf2), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        out[0] = rq; out[1] = rr;
    }

    private static int hexDist(int q1, int r1, int q2, int r2) {
        int dq = q1 - q2, dr = r1 - r2;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    private static long hexKey(int q, int r) {
        return ((long) q << 32) | (r & 0xFFFFFFFFL);
    }

    private static float roadCenterF(float tx) {
        return 2.2f * (float) Math.sin(tx * 0.12f) + 1.5f * (float) Math.sin(tx * 0.05f + 1.7f);
    }

    private boolean hexBlocked(int q, int r) {
        if (storyMode) return false;
        hexToWorld(q, r, HO_F);
        int tx0 = (int) Math.floor(HO_F[0] / TILE), ty0 = (int) Math.floor(HO_F[1] / TH);
        for (int ty = ty0 - 1; ty <= ty0 + 1; ty++) {
            for (int tx = tx0 - 1; tx <= tx0 + 1; tx++) {
                int h = (tx * 40503) ^ (ty * 66827);
                int roll = (h >>> 3) % 100;
                boolean large = roll < 8 && !props2.isEmpty();
                boolean small = !large && roll < 22 && !props.isEmpty();
                if (!large && !small) continue;
                Bitmap pr = large ? props2.get((h >>> 5) % props2.size())
                                  : props.get((h >>> 5) % props.size());
                float var = large ? (0.85f + ((h >>> 13) & 31) / 31f * 0.45f)
                                  : (0.8f + ((h >>> 15) & 31) / 31f * 0.5f);
                float s = ((large ? TH * 2.43f : TH * 0.45f) / pr.getHeight()) * var;
                float ax = large ? tx * TILE + TILE * (0.3f + ((h >>> 9) & 127) / 127f * 0.4f)
                                 : tx * TILE + TILE * (0.25f + ((h >>> 9) & 127) / 127f * 0.5f);
                float ay = large ? (ty + 1) * TH - TH * 0.10f
                                 : (ty + 1) * TH - TH * (0.15f + ((h >>> 11) & 31) / 31f * 0.25f);
                if (large) {
                    float bw = Math.min(pr.getWidth() * s * 0.35f, HEX * 0.9f);
                    float fh = TH * 0.8f;
                    if (HO_F[0] >= ax - bw && HO_F[0] <= ax + bw
                            && HO_F[1] >= ay - fh && HO_F[1] <= ay + TH * 0.25f) {
                        return true;
                    }
                } else {
                    worldToHex(ax, ay, HO_A);
                    if (HO_A[0] == q && HO_A[1] == r) return true;
                }
            }
        }
        return false;
    }

    private Player nearestHero(Enemy en) {
        Player best = player; float bd = Float.MAX_VALUE;
        for (Player p : party) {
            if (p.hp <= 0) continue;
            float dx = p.x - en.x, dy = p.y - en.y;
            float d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private boolean hexOccupied(int q, int r, Enemy self) {
        for (Player p : party) {
            worldToHex(p.x, p.y, HO_A);
            if (HO_A[0] == q && HO_A[1] == r) return true;
        }
        for (Enemy en : enemies) {
            if (en.dead || en == self) continue;
            worldToHex(en.x, en.y, IH_C);
            if (IH_C[0] == q && IH_C[1] == r) return true;
        }
        return false;
    }

    private boolean storyWalkHex(int q, int r) {
        if (!storyMode || story == null) return true;
        hexToWorld(q, r, HO_F);
        return StoryWorld.sceneWalkable(HO_F[0], HO_F[1]);
    }

    private boolean hexFree(int q, int r, Enemy self) {
        return storyWalkHex(q, r) && !hexOccupied(q, r, self) && !hexBlocked(q, r);
    }

    private void spawnEnemy() {
        boolean heavy = Math.random() < 0.3;
        for (int tries = 0; tries < 12; tries++) {
            float a = (float) (Math.random() * Math.PI * 2);
            float x = player.x + (float) Math.cos(a) * HEX * 7;
            float y = player.y + (float) Math.sin(a) * HEX * 7 * SQUASH * 2;
            worldToHex(x, y, IH_A);
            if (!hexFree(IH_A[0], IH_A[1], null)) continue;
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            Enemy e = new Enemy();
            e.x = FW_A[0]; e.y = FW_A[1];
            if (heavy) {
                e.heavy = true;
                e.hp = 60; e.maxHp = 60;
                e.mana = 100; e.maxMana = 100;
                e.weapon = 2;
                e.speed = 130f;
            }
            enemies.add(e);
            return;
        }
    }

    private void startPlayerTurn() {
        phase = PH_PLAYER; phaseT = 0;
        for (Player p : party) p.actionsLeft = 2;
        sound.play("turn");
        attackRangeShown = 0;
        targetEnemy = null;
        hexesShown = false;
        fanDirty = true;
        mana = Math.min(100, mana + 25);
        if (!storyFight && !storyMode && enemies.size() < 5) spawnEnemy();
        sound.play(voice + "_turn");
    }

    private void endPlayerTurn() {
        sound.play("turn");
        if (storyMode) sw().onPlayerTurnEnd();
        phase = PH_ENEMY; phaseT = 0;
        hexesShown = false; targetEnemy = null; attackRangeShown = 0;
        fanDirty = true; atkDirty = true;
        for (Enemy en : enemies) {
            en.resetTurn();
            if (en.beast && en.mana < en.maxMana)
                en.mana = Math.min(en.maxMana, en.mana + BEAST_MANA_REGEN);
        }
        for (Player p : party) {
            Integer bt = bleedTurns.get(p);
            if (bt != null && bt > 0) {
                int bd = bleedDmg.get(p);
                if (!storyTest) p.hp -= bd;
                addDmg(p.x, p.y - PLAYER_H - 20, -bd, C_BLOOD);
                sound.play("hit");
                bleedTurns.put(p, bt - 1);
            }
        }
        for (Enemy en : enemies) {
            if (en.dead || en.poisonTurns <= 0) continue;
            en.poisonTurns--;
            spawnWoosh(en.x, en.y);
            sound.play("poison");
            hurtEnemy(en, 5);
            addDmg(en.x, en.y - ENEMY_H - 44, 5, C_CYAN);
        }
        reserved.clear();
        buildFlow();
    }

    private void buildFlow() {
        flow.clear();
        worldToHex(player.x, player.y, IH_A);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.addLast(new int[] { IH_A[0], IH_A[1] });
        flow.put(hexKey(IH_A[0], IH_A[1]), 0);
        while (!q.isEmpty()) {
            int[] c = q.pollFirst();
            int d = flow.get(hexKey(c[0], c[1]));
            if (d >= 30) continue;
            for (int[] n : NEIGH6) {
                int nq = c[0] + n[0], nr = c[1] + n[1];
                long k = hexKey(nq, nr);
                if (flow.containsKey(k) || hexBlocked(nq, nr) || !storyWalkHex(nq, nr)) continue;
                flow.put(k, d + 1);
                q.addLast(new int[] { nq, nr });
            }
        }
    }

    private void resetFightKeepParty() {
        for (Player p : party) { p.hp = 100; p.cried = false; p.actionsLeft = 2; }
        mana = 100;
        enemies.clear();
        for (Dmg d : dmgPool) d.active = false;
        for (Bolt bo : boltPool) bo.active = false;
        for (Blast bl : blastPool) bl.active = false;
        for (Puff pf : puffPool) pf.active = false;
        for (Particle pa : particlePool) pa.active = false;
        for (Decal dcl : decalPool) dcl.active = false;
        for (Slash s : slashPool) s.active = false;
        camSnap = false;
        for (int i = 0; i < 3; i++) spawnEnemy();
        startPlayerTurn();
    }

    private void resetFight() {
        resetFightKeepParty();
    }

    private boolean canAct() { return player.hp > 0 && player.actionsLeft > 0; }

    private void addDmg(float x, float y, int val) { addDmg(x, y, val, 0); }

    private void addDmg(float x, float y, int val, int col) {
        for (Dmg d : dmgPool) {
            if (!d.active) {
                d.x = x; d.y = y; d.val = val; d.col = col;
                d.txt = String.valueOf(val);
                d.t = 0; d.active = true;
                return;
            }
        }
    }

    private void spawnPuff(float x, float y) {
        for (Puff p : puffPool) {
            if (!p.active) {
                p.x = x; p.y = y; p.t = 0; p.active = true;
                return;
            }
        }
    }

    private void spawnBlast(float x, float y) {
        for (Blast b : blastPool) {
            if (!b.active) {
                b.x = x; b.y = y; b.t = 0; b.active = true;
                return;
            }
        }
    }

    private void spawnBolt(float x0, float y0, float tx, float ty, Enemy tgt, int dmg) {
        for (Bolt b : boltPool) {
            if (!b.active) {
                b.x0 = x0; b.y0 = y0; b.tx = tx; b.ty = ty;
                b.x = x0; b.y = y0; b.t = 0; b.tgt = tgt; b.dmg = dmg; b.active = true;
                return;
            }
        }
    }

    private void drawEkg(Canvas cv, float x, float y, int alpha) {
        ekgPath.reset();
        ekgPath.moveTo(x, y);
        ekgPath.lineTo(x + 16, y);
        ekgPath.lineTo(x + 20, y - 6);
        ekgPath.lineTo(x + 26, y + 13);
        ekgPath.lineTo(x + 30, y - 7);
        ekgPath.lineTo(x + 58, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setAlpha(alpha);
        cv.drawPath(ekgPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0);
    }

    private void spawnDecal(float x, float y) {
        if (splatterBmp == null) return;
        for (Decal dcl : decalPool) {
            if (!dcl.active) {
                dcl.x = x; dcl.y = y;
                dcl.rot = (float) (Math.random() * 360);
                dcl.flip = Math.random() > 0.5f ? 1f : -1f;
                dcl.s = 0.28f + (float) Math.random() * 0.22f;
                dcl.active = true;
                return;
            }
        }
    }

    private void drawDecals(Canvas cv) {
        if (splatterBmp == null) return;
        for (Decal dcl : decalPool) {
            if (!dcl.active) continue;
            float w = splatterBmp.getWidth() * dcl.s * zoom * 0.5f;
            float h = splatterBmp.getHeight() * dcl.s * zoom * 0.5f;
            cv.save();
            cv.translate(sx(dcl.x), sy(dcl.y));
            cv.scale(dcl.flip, 1);
            cv.rotate(dcl.rot);
            paint.setAlpha(140);
            rf.set(-w / 2f, -h / 2f, w / 2f, h / 2f);
            cv.drawBitmap(splatterBmp, null, rf, paint);
            paint.setAlpha(255);
            cv.restore();
        }
    }

    private void emberBurst() {
        if (quality <= 0) return;
        for (int i = 0; i < 24; i++) {
            for (Particle p : particlePool) {
                if (!p.active) {
                    p.x = player.x; p.y = player.y - 100;
                    p.vx = (float) (Math.random() * 240 - 120);
                    p.vy = (float) (-Math.random() * 260 - 60);
                    p.grav = 400;
                    p.life = 0.7f + (float) Math.random() * 0.6f;
                    p.t = 0;
                    p.col = (i & 1) == 0 ? C_EMBER : C_MAGENTA;
                    p.active = true;
                    break;
                }
            }
        }
    }

    private void spawnSlash(float x, float y) {
        for (Slash s : slashPool) {
            if (!s.active) {
                s.x = x; s.y = y - ENEMY_H * 0.5f;
                s.rot = (float) (Math.random() * 360);
                s.t = 0; s.active = true;
                return;
            }
        }
    }

    private void drawSlashes(Canvas cv) {
        for (Slash s : slashPool) {
            if (!s.active) continue;
            float k = s.t / 0.22f;
            float r = (26 + k * 64) * zoom;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f * zoom * (1 - k));
            paint.setColor(C_MAGENTA);
            paint.setAlpha((int) (150 * (1 - k)));
            rf.set(sx(s.x) - r, sy(s.y) - r * SQUASH, sx(s.x) + r, sy(s.y) + r * SQUASH);
            cv.drawArc(rf, s.rot, 110, false, paint);
            paint.setColor(C_BONE);
            paint.setStrokeWidth(1.5f * zoom * (1 - k));
            paint.setAlpha((int) (200 * (1 - k)));
            cv.drawArc(rf, s.rot + 20, 70, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0);
            paint.setAlpha(255);
        }
    }

    private void spawnWoosh(float x, float y) {
        for (int i = 0; i < 10; i++) {
            for (Particle p : particlePool) {
                if (!p.active) {
                    p.x = x + (float) (Math.random() * 44 - 22);
                    p.y = y - (float) (Math.random() * 90);
                    p.vx = (float) (Math.random() * 20 - 10);
                    p.vy = -60 - (float) (Math.random() * 70);
                    p.grav = -40;
                    p.life = 0.5f + (float) Math.random() * 0.3f;
                    p.t = 0;
                    p.col = C_CYAN;
                    p.active = true;
                    break;
                }
            }
        }
    }

    private void spawnDeathParticles(float x, float y) {
        for (int i = 0; i < 15; i++) {
            for (Particle p : particlePool) {
                if (!p.active) {
                    p.x = x; p.y = y;
                    p.vx = (float)(Math.random() * 100 - 50);
                    p.vy = (float)(Math.random() * -150 - 50);
                    p.grav = 400;
                    p.life = 0.5f + (float)Math.random() * 0.5f;
                    p.t = 0;
                    p.col = (Math.random() > 0.5) ? C_BLOOD : 0xFF050508;
                    p.active = true;
                    break;
                }
            }
        }
    }

    private void hurtEnemy(Enemy en, int dmg) {
        if (en.dead) return;
        boolean dying = en.hp - dmg <= 0;
        en.hp -= dmg;
        en.hitFlash = 0.25f;
        addDmg(en.x, en.y - ENEMY_H - 20, dmg);
        sound.play("hit");
        if (!dying) {
            if (dmg >= en.maxHp * 0.7f) {
                sound.play(en.gender == 1 ? "female_cry" : "male_cry");
            } else {
                sound.play(en.beast ? "beast_hurt" : (en.gender == 1 ? "female_hurt" : "male_hurt"));
            }
        }

        if (en.hp <= 0) {
            en.dead = true;
            spawnDecal(en.x, en.y);
            sound.play("death");
            sound.play(en.beast ? "beast_death" : (en.gender == 1 ? "female_death" : "male_death"));
            sound.play(voice + "_kill");
            if (storyMode) {
                sw().onEnemyDeath();
                sw().onEnemyCountLow(enemies.size() - 1);
            }
        }
    }

    private void planEnemy(Enemy en) {
        en.planned = true;
        Player tgt = nearestHero(en);
        worldToHex(tgt.x, tgt.y, IH_A);
        worldToHex(en.x, en.y, IH_B);
        int dist = hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]);
        if (en.heavy && en.mana >= Enemy.HEAVY_ATK_MANA[1] && dist <= Enemy.HEAVY_ATK_RANGE[1]) {
            en.atkForm = 2;
            en.attacksPlanned = 1;
            en.intent = 1;
            return;
        }
        if (en.beast) {
            if (dist <= 1) {
                en.atkForm = (en.mana >= en.maxMana) ? 2 : 3;
                en.attacksPlanned = 1; en.intent = 1; return;
            }
            if (dist <= BEAST_B1_RANGE) { en.atkForm = 1; en.attacksPlanned = 1; en.intent = 1; return; }
        }
        if (dist <= 1) {
            en.atkForm = en.heavy ? 1 : 0;
            en.attacksPlanned = (en.heavy || en.beast) ? 1 : 2;
            en.intent = 1;
            return;
        }
        int steps = dist - 1; if (steps > 3) steps = 3;
        en.attacksPlanned = 1;
        if (en.heavy) en.atkForm = 1;
        int cq = IH_B[0], cr = IH_B[1];
        en.pathLen = 0;
        if (flow.get(hexKey(cq, cr)) != null) {
            for (int s = 0; s < steps; s++) {
                int bd = Integer.MAX_VALUE, bq = cq, br = cr;
                for (int[] n : NEIGH6) {
                    int nq = cq + n[0], nr = cr + n[1];
                    Integer nd = flow.get(hexKey(nq, nr));
                    if (nd == null || nd >= bd) continue;
                    if (!hexFree(nq, nr, en)) continue;
                    bd = nd; bq = nq; br = nr;
                }
                if (bq == cq && br == cr) break;
                cq = bq; cr = br;
                hexToWorld(cq, cr, FW_A);
                en.pathX[en.pathLen] = FW_A[0];
                en.pathY[en.pathLen] = FW_A[1];
                en.pathLen++;
            }
            if (en.pathLen > 0) en.intent = 2;
            reserved.add(hexKey(cq, cr));
        } else {
            float dx = tgt.x - en.x, dy = tgt.y - en.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 1) {
                float[] stp = { HEX * 3.2f, HEX * 1.6f };
                for (float st : stp) {
                    worldToHex(en.x + dx / d * st, en.y + dy / d * st, IH_C);
                    long k = hexKey(IH_C[0], IH_C[1]);
                    if (hexFree(IH_C[0], IH_C[1], en) && !reserved.contains(k)) {
                        hexToWorld(IH_C[0], IH_C[1], FW_A);
                        en.pathX[en.pathLen] = FW_A[0];
                        en.pathY[en.pathLen] = FW_A[1];
                        en.pathLen++;
                        en.intent = 2;
                        reserved.add(k);
                        break;
                    }
                }
            }
        }
    }

    // legacy sandbox test mode removed — story test mode replaces it.

    private void startGame(Hero h) {
        party.clear();
        Player a = new Player(); a.hero = h;
        worldToHex(640, 640, IH_A);
        hexToWorld(IH_A[0], IH_A[1], FW_A);
        a.x = a.targetX = FW_A[0]; a.y = a.targetY = FW_A[1];
        party.add(a);
        player = a;
        voice = h.voice;
        resetFightKeepParty();
        // Guarantee at least one heavy in the opening wave so it's testable immediately.
        boolean hasHeavy = false;
        for (Enemy e : enemies) if (e.heavy) { hasHeavy = true; break; }
        if (!hasHeavy && !enemies.isEmpty()) {
            Enemy e = enemies.get(0);
            e.heavy = true;
            e.hp = 60; e.maxHp = 60;
            e.mana = 100; e.maxMana = 100;
            e.weapon = 2;
        }
        state = STATE_GAME;
    }

    private void startStory() { state = STATE_CHAPTER; }

    private void beginAct1() {
        story = new Story(this);
        storyMode = true;
        storyFight = false;
        if (map == null) {
            map = new SceneMap(getContext(), quality > 0);
            actors = new StoryActors();
        }
        sw().attach(map, actors, this);
        sw().reload();

        party.clear();
        Player a = new Player(); a.hero = roster[0];
        worldToHex(640, 640, IH_A);
        hexToWorld(IH_A[0], IH_A[1], FW_A);
        a.x = a.targetX = FW_A[0]; a.y = a.targetY = FW_A[1];
        party.add(a);
        player = a;
        voice = a.hero.voice;

        for (Player p : party) { p.hp = 100; p.cried = false; p.actionsLeft = 2; }
        mana = 100;

        enemies.clear();
        for (Dmg d : dmgPool) d.active = false;
        for (Bolt b : boltPool) b.active = false;
        for (Blast b : blastPool) b.active = false;
        for (Puff p : puffPool) p.active = false;
        for (Slash s : slashPool) s.active = false;
        for (Decal dcl : decalPool) dcl.active = false;
        camSnap = false;
        state = STATE_GAME;
        startPlayerTurn();
        story.start();
    }

    @Override public void shTeleport(float x, float y) {
        player.x = x; player.y = y; player.targetX = x; player.targetY = y; camSnap = false;
    }
    @Override public boolean shPlayerArrived() { return !player.isMoving(); }
    @Override public void shClearEnemies() { enemies.clear(); fanDirty = true; }
    @Override public android.content.Context shGetContext() { return getContext(); }

    private void drawChapter(Canvas cv) {
        if (menuBmp != null) { rf.set(0, 0, W, H); cv.drawBitmap(menuBmp, null, rf, paint); }
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fLogo);
        paint.setTextSize(64);
        paint.setColor(C_BONE);
        cv.drawText("STORY", W / 2f, H * 0.3f, paint);
        chapterBtn.set(W / 2f - 160, H * 0.45f, W / 2f + 160, H * 0.45f + 90);
        drawMenuButton(cv, chapterBtn, "ACT 1", C_MAGENTA, menuPress == 7, true);
        if (overlay != null) { rf.set(0, 0, W, H); cv.drawBitmap(overlay, null, rf, paint); }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private boolean onChapterTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            menuPress = 0;
            if (chapterBtn.contains(e.getX(), e.getY())) menuPress = 7;
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            if (menuPress == 7 && chapterBtn.contains(e.getX(), e.getY())) {
                sound.play("ui"); beginAct1();
            }
            menuPress = 0;
            return true;
        }
        return true;
    }

    private void beginStoryFight() {
        int n = story.fightRequest;
        if (n <= 0) n = 2;
        story.fightRequest = 0;
        storyFight = true;
        for (Player p : party) { p.hp = 100; p.cried = false; p.actionsLeft = 2; }
        mana = 100;
        for (Dmg d : dmgPool) d.active = false;
        for (Bolt b : boltPool) b.active = false;
        for (Blast b : blastPool) b.active = false;
        for (Puff p : puffPool) p.active = false;
        for (Slash s : slashPool) s.active = false;
        for (Decal dcl : decalPool) dcl.active = false;
        camSnap = false;
        enemies.clear();
        for (int i = 0; i < n; i++) spawnStoryEnemy();
        fanDirty = true;
        scriptCamRelease();
        state = STATE_GAME;
        startPlayerTurn();
    }

    private void spawnStoryEnemy() {
        for (int tries = 0; tries < 40; tries++) {
            float a = (float) (Math.random() * Math.PI * 2);
            float d = HEX * (4 + (float) (Math.random() * 3));
            float x = player.x + (float) Math.cos(a) * d;
            float y = player.y + (float) Math.sin(a) * d;
            if (!StoryWorld.sceneWalkable(x, y)) continue;
            worldToHex(x, y, IH_A);
            if (!hexFree(IH_A[0], IH_A[1], null)) continue;
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            Enemy e = new Enemy();
            e.x = FW_A[0]; e.y = FW_A[1];
            e.facing = -1;
            enemies.add(e);
            return;
        }
    }

    private static int pi(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private StoryWorld storyWorld;
    private StoryWorld sw() {
        if (storyWorld == null) storyWorld = StoryWorld.get(getContext(), sound);
        return storyWorld;
    }
    private int storyEnemiesAlive() {
        return enemies == null ? 0 : enemies.size();
    }

    private void processStoryAction(String act) {
        if (act.startsWith("ACTION shake")) { shakeT = 0.2f; return; }
        if (act.startsWith("ACTION flash")) { hurtT = 0.15f; return; }
        if (act.startsWith("ACTION slash")) {
            sw().resolveActionPoint(act, player.x + player.facing * 60, player.y);
            spawnSlash(sw().pt[0], sw().pt[1]);
            sound.play("hit");
            return;
        }
        if (act.startsWith("ACTION blood")) {
            sw().resolveActionPoint(act, player.x + player.facing * 60, player.y);
            spawnDecal(sw().pt[0], sw().pt[1]);
            return;
        }
        if (act.startsWith("SHOW ") || act.startsWith("HIDE ") || act.startsWith("WALK ")
                || act.startsWith("EXIT ") || act.startsWith("ACTOR ")) {
            sw().npcCommand(act);
            return;
        }
    }

    private void updateEmbers(float dt) {
        for (Ember em : embers) {
            em.y -= em.s * dt;
            em.x += (float) Math.sin(em.y * 0.02f) * 12 * dt;
            if (em.y < -10) { em.y = H + 10; em.x = (float) (Math.random() * W); }
        }
    }

    private void update(float dt) {
        loadT += dt;
        if (assetsReady && pending != null) applyAssets();
        updateEmbers(dt);
        if (state == STATE_MENU || state == STATE_SELECT || state == STATE_CHAPTER) return;

        // hard guard: a non-finite camera/zoom/player makes every world draw a no-op.
        if (!Float.isFinite(player.x) || !Float.isFinite(player.y)) {
            worldToHex(640, 640, IH_A);
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            player.x = player.targetX = FW_A[0]; player.y = player.targetY = FW_A[1];
            swActive = false;
        }
        if (!Float.isFinite(camX) || !Float.isFinite(camY) || !Float.isFinite(zoom)
                || !Float.isFinite(zoomPunch) || (zoom + zoomPunch) <= 0.01f) {
            zoom = 1.25f; zoomPunch = 0f; pushT = -1f;
            camX = player.x; camY = player.y - (H * 0.28f) / zoom;
            camSnap = true; exploring = false; flingX = 0; flingY = 0;
        }

        if (storyMode && story != null && state == STATE_GAME) {
            story.update(dt);
            if (actors != null) {
                actors.update(dt);
                for (int i = 0; i < actors.size(); i++) {
                    StoryActor a = actors.get(i);
                    if (a.idleFrames == null) {
                        a.idleFrames = idlePoolFor(a.type);
                        a.glideFrames = glidePoolFor(a.type);
                    }
                }
            }
            if (story.quitRequested || story.ended) {
                story = null; storyMode = false; storyFight = false; state = STATE_MENU; return;
            }

            int[] ph = new int[2];
            worldToHex(player.x, player.y, ph);
            sw().onPlayerHexChanged(ph[0], ph[1]);

            sw().update();

            if (sw().takeSceneEvent()) {
                shClearEnemies();
                storyFight = false;
                camX = sw().snapX;
                camY = sw().snapY;
                camSnap = true;
                fanDirty = true;
            }

            if (story.hasObjective && !player.isMoving()) {
                worldToHex(player.x, player.y, IH_A);
                if (IH_A[0] == story.objectiveQ && IH_A[1] == story.objectiveR) {
                    story.onObjectiveReached();
                }
            }
        }
        if (storyMode && story != null && story.dialogUp) {
            if (camMode == 0) {
                if (!camSnap && H > 0) { camX = player.x; camY = player.y - (H * 0.28f) / zoom; camSnap = true; }
                float kk = 1 - (float) Math.exp(-dt * 8);
                camX += (player.x - camX) * kk;
                camY += ((player.y - (H * 0.28f) / zoom) - camY) * kk;
            } else {
                updateDirector(dt);
            }
            for (Player p : party) p.update(dt);
            for (Particle p : particlePool) {
                if (!p.active) continue;
                p.t += dt;
                if (p.t >= p.life) { p.active = false; continue; }
                p.vy += p.grav * dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
            }
            for (Puff p : puffPool) if (p.active) { p.t += dt; if (p.t > 0.5f) p.active = false; }
            for (Slash s : slashPool) if (s.active) { s.t += dt; if (s.t > 0.22f) s.active = false; }
            for (Dmg d : dmgPool) if (d.active) { d.t += dt; if (d.t > 0.8f) d.active = false; }
            return;
        }

        if (shakeT > 0) {
            shakeT -= dt;
            float mag = shakeT * 40f;
            shakeX = (float)(Math.random() * mag * 2 - mag);
            shakeY = (float)(Math.random() * mag * 2 - mag);
        } else { shakeX = 0; shakeY = 0; }

        if (hitstopT > 0) {
            hitstopT -= dt;
            for (Puff p : puffPool) if (p.active) p.t += dt;
            for (Dmg d : dmgPool) if (d.active) d.t += dt;
            return;
        }

        if (deadT > 0) {
            deadT -= dt;
            if (deadT <= 0) {
                if (storyFight) {
                    story.fightLost();
                    beginStoryFight();
                } else {
                    resetFight();
                }
            }
            // C6: let FX keep ticking so particles don't freeze mid-air
            for (Particle p : particlePool) {
                if (!p.active) continue;
                p.t += dt;
                if (p.t >= p.life) { p.active = false; continue; }
                p.vy += p.grav * dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
            }
            for (Dmg d : dmgPool) if (d.active) { d.t += dt; if (d.t > 0.8f) d.active = false; }
            for (Puff p : puffPool) if (p.active) { p.t += dt; if (p.t > 0.5f) p.active = false; }
            for (Slash s : slashPool) if (s.active) { s.t += dt; if (s.t > 0.22f) s.active = false; }
            for (Bolt b : boltPool) if (b.active) { b.t += dt; if (b.t >= 0.28f) b.active = false; }
            for (Blast bl : blastPool) if (bl.active) { bl.t += dt; if (bl.t > 0.5f) bl.active = false; }
            return;
        }

        if (!camSnap && H > 0) {
            camX = player.x;
            camY = player.y - (H * 0.28f) / zoom;
            camSnap = true;
        }
        updateScriptWalk(dt);
        updateDirector(dt);

        phaseT += dt;
        if (hurtT > 0) hurtT -= dt;
        float dockTarget = (phase == PH_PLAYER && deadT <= 0) ? 1 : 0;
        dockSlide += (dockTarget - dockSlide) * (1 - (float) Math.exp(-dt * 10));
        for (Player p : party) p.update(dt);
        if (zoomPunch > 0) zoomPunch = Math.max(0, zoomPunch - dt * 3f);
        if (scriptZoom) {
            zoom += (zoomTarget - zoom) * (1 - (float) Math.exp(-dt * 4f));
        }
        for (int i = 0; i < enemies.size(); i++) {
            Enemy en = enemies.get(i);
            if (en.beast && en.attackT > 0.01f && en.attackT < en.attackDuration) {
                if (en.beastForm == 3 && !biteFired.contains(en)
                        && en.attackT > en.attackDuration * 0.5f) {
                    biteFired.add(en);
                    sound.play("beast_bite");
                    Player tgt = nearestAlive(en.x, en.y);
                    if (tgt != null) spawnBite(tgt.x, tgt.y);
                }
            } else if (en.attackT <= 0.01f) {
                biteFired.remove(en);
            }
        }

        if (!panning && !player.isMoving() && camMode == 0 && !swActive && (flingX != 0 || flingY != 0)) {
            camX += flingX * dt;
            camY += flingY * dt;
            float dk = (float) Math.exp(-dt * 3f);
            flingX *= dk; flingY *= dk;
            if (flingX * flingX + flingY * flingY < 400) { flingX = 0; flingY = 0; }
            exploring = true;
            exploreT = 0;
        }
        if (exploring) {
            exploreT += dt;
            if (exploreT > 6 || player.isMoving()) exploring = false;
        }
        float fx = player.x, fy = player.y;
        if (phase == PH_ENEMY) {
            Enemy ae = null;
            for (Enemy en2 : enemies) {
                if (!en2.dead && !en2.acted) { ae = en2; break; }
            }
            if (ae != null) {
                worldToHex(player.x, player.y, IH_A);
                worldToHex(ae.x, ae.y, IH_B);
                if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= 8
                        && (ae.pathI < ae.pathLen || ae.attacking())) {
                    fx = ae.x;
                    fy = ae.y;
                }
            }
        }
        if (!exploring && H > 0 && camMode == 0) {
            float k = 1 - (float) Math.exp(-dt * 8);
            float tx = fx, ty = fy - (H * 0.28f) / zoom;
            if (camLookT > 0) {
                camLookT -= dt;
                tx = camLookX;
                ty = camLookY - (H * 0.28f) / zoom;
            }
            camX += (tx - camX) * k;
            camY += (ty - camY) * k;
        }
        runeT += dt;
        if (fadeState == 1) {
            fadeT += dt / (fadeDur / 1000f);
            if (fadeT >= 1f) { fadeT = 1f; fadeState = 2; }
        } else if (fadeState == 3) {
            fadeT -= dt / (fadeDur / 1000f);
            if (fadeT <= 0f) { fadeT = 0f; fadeState = 0; }
        }
        if (storyMode && map != null) {
            worldToHex(player.x, player.y, IH_A);
            map.setPlayerHex(IH_A[0], IH_A[1]);
        }

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            en.animT += dt;
            if (en.hitFlash > 0) en.hitFlash -= dt;
            if (en.dead) {
                en.deathT += dt;
                en.floater.moving = false;
                en.floater.update(dt);
                if (en.deathT > 0.7f) {
                    spawnDeathParticles(en.x, en.y);
                    enemies.remove(i);
                    fanDirty = true;
                    if (enemies.isEmpty()) {
                        if (storyFight) {
                            storyFight = false;
                            hexesShown = false;
                            attackRangeShown = 0;
                            targetEnemy = null;
                        }
                        if (sw().encounterLive()) sw().endEncounter();
                        else { sound.play(voice + "_victory"); emberBurst(); }
                    }
                }
            }
        }

        int ev;
        while ((ev = player.hero.pollEvent()) != 0) {
            Hero h = player.hero;
            Hero.Attack atk = h.cur;
            if (ev == Hero.EV_SHAKE) {
                shakeT = 0.15f;
            } else if (ev == Hero.EV_STRIKE) {
                hitstopT = 0.05f;
                shakeT = 0.15f;
                zoomPunch = 0.06f;
                if (h.target != null && !h.target.dead) {
                    spawnSlash(h.target.x, h.target.y);
                    hurtEnemy(h.target, atk.dmg);
                }
            } else if (ev == Hero.EV_BOLT) {
                if (h.target != null && !h.target.dead) {
                    spawnBolt(player.x + player.facing * 40, player.y - PLAYER_H * 0.75f,
                              h.target.x, h.target.y - ENEMY_H * 0.5f, h.target, atk.dmg);
                }
            } else if (ev == Hero.EV_AOE) {
                spawnBlast(player.x, player.y);
                shakeT = 0.15f;
                zoomPunch = 0.10f;
                worldToHex(player.x, player.y, IH_A);
                for (Enemy en : enemies) {
                    if (en.dead) continue;
                    worldToHex(en.x, en.y, IH_B);
                    if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= atk.range) {
                        spawnSlash(en.x, en.y);
                        hurtEnemy(en, atk.dmg);
                    }
                }
            } else if (ev == Hero.EV_POISON) {
                if (h.target != null && !h.target.dead) {
                    h.target.poisonTurns = 4;
                    spawnWoosh(h.target.x, h.target.y);
                }
            }
        }

        for (Bolt b : boltPool) {
            if (!b.active) continue;
            b.t += dt;
            float kk = b.t / 0.28f;
            if (kk >= 1f) {
                if (!b.tgt.dead) {
                    hurtEnemy(b.tgt, b.dmg);
                    hitstopT = 0.04f;
                    shakeT = 0.1f;
                }
                b.active = false;
            } else {
                b.x = b.x0 + (b.tx - b.x0) * kk;
                b.y = b.y0 + (b.ty - b.y0) * kk - (float) Math.sin(kk * Math.PI) * 40;
            }
        }

        for (Blast bl : blastPool) {
            if (!bl.active) continue;
            bl.t += dt;
            if (bl.t > 0.5f) bl.active = false;
        }
        for (Slash s : slashPool) {
            if (!s.active) continue;
            s.t += dt;
            if (s.t > 0.22f) s.active = false;
        }

        for (Particle p : particlePool) {
            if (!p.active) continue;
            p.t += dt;
            if (p.t >= p.life) { p.active = false; continue; }
            p.vy += p.grav * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        if (phase == PH_PLAYER) {
            // player turn waits for input
        } else {
            // A1: flag-based iteration — safe even if enemies are removed mid-loop
            Enemy active = null;
            for (Enemy en : enemies) {
                if (!en.dead && !en.acted) { active = en; break; }
            }
            if (active != null) {
                if (!active.planned) {
                    planEnemy(active);
                    if (active.attacksPlanned > 0) active.intent = 1;
                    else if (active.pathLen > 0) active.intent = 2;
                }
                Player tgt = nearestHero(active);
                worldToHex(tgt.x, tgt.y, IH_A);
                worldToHex(active.x, active.y, IH_B);
                boolean adj = hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) == 1;
                boolean inR2 = hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= Enemy.HEAVY_ATK_RANGE[1];
                boolean inLunge = hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= BEAST_B1_RANGE;
                boolean wasAttacking = active.attacking();
                boolean wasMoving = active.floater.moving;
                int prevAtkPos = active.atkPos;
                active.turnUpdate(dt, tgt.x, tgt.y, adj, inR2, inLunge);
                if (active.beast && active.floater.moving && !wasMoving) sound.play("beast_move");
                if ((!wasAttacking && active.attacking())) {
                    sound.play(active.weapon == 1 ? "claw" : "swing");
                    if (active.beast) {
                        active.attackDuration = active.beastForm == 1 ? Enemy.BEAST_ATK_DUR[0] : (active.beastForm == 2 ? Enemy.BEAST_ATK_DUR[1] : Enemy.BEAST_ATK_DUR[2]);
                        sound.play("beast_attack");
                        sound.play(active.beastForm == 1 ? "beast_lunge" : (active.beastForm == 2 ? "beast_slam" : "beast_bite"));
                        if (active.beastForm == 1) beginBeastLeap(active, tgt);
                    }
                }
                if (active.beast && active.attacking()) stepBeastLeap(active, tgt);
                else if (active.beast) leapOff.remove(active);
                // Strike detection
                boolean didStrike = false;
                if (active.attacking() && !active.struck) {
                    if (active.heavy) {
                        int form = active.atkForm < 1 ? 1 : active.atkForm;
                        int[] seq = Enemy.HEAVY_ATK_SEQ[form - 1];
                        int strikeAt = Enemy.HEAVY_ATK_STRIKE[form - 1];
                        if (active.atkPos > prevAtkPos && active.atkPos >= strikeAt) didStrike = true;
                    } else if (active.beast) {
                        int[] seq = Enemy.BEAST_ATK_SEQ[active.beastForm - 1];
                        int strikeAt = Enemy.BEAST_ATK_STRIKE[active.beastForm - 1];
                        if (active.atkPos > prevAtkPos && active.atkPos >= strikeAt) didStrike = true;
                    } else {
                        if (active.attackT > 0.45f) didStrike = true;
                    }
                }
                if (didStrike) {
                    active.struck = true;
                    if (active.beast && active.beastForm == 1) {
                        // Lunge
                        sound.play("swing");
                        shakeT = Math.max(shakeT, 0.15f);
                        spawnSlash(tgt.x, tgt.y);
                        for (int i = 0; i < 4; i++)
                            spawnPuff(tgt.x + (float) (Math.random() * 50 - 25), tgt.y + (float) (Math.random() * 14 - 7));
                        int dmg = Enemy.BEAST_ATK_DMG[0];
                        if (!storyTest) tgt.hp -= dmg;
                        hurtT = 0.3f;
                        addDmg(tgt.x, tgt.y - PLAYER_H - 20, -dmg);
                        sound.play("hit");
                        if (tgt.hp > 0) {
                            sound.play("hurt");
                            sound.play(tgt.hero.voice + "_hurt");
                            post(hapticRun);
                            if (tgt.hp <= 30 && !tgt.cried) { tgt.cried = true; sound.play(tgt.hero.voice + "_wounded"); }
                        }
                        boolean allDeadB1 = true;
                        for (Player p : party) if (p.hp > 0) allDeadB1 = false;
                        if (allDeadB1) { deadT = 2f; sound.play(tgt.hero.voice + "_death"); }
                    } else if (active.beast && active.beastForm == 2) {
                        // Slam
                        active.mana = 0;
                        sound.play("blast");
                        spawnBlast(active.x, active.y);
                        shakeT = Math.max(shakeT, 0.3f);
                        zoomPunch = 0.15f;
                        int dmg = Enemy.BEAST_ATK_DMG[1];
                        if (!storyTest) tgt.hp -= dmg;
                        bleedTurns.put(tgt, 2);
                        bleedDmg.put(tgt, (int)(dmg * 0.2f));
                        hurtT = 0.4f;
                        addDmg(tgt.x, tgt.y - PLAYER_H - 20, -dmg);
                        sound.play("hit");
                        if (tgt.hp > 0) {
                            sound.play("hurt");
                            sound.play(tgt.hero.voice + "_hurt");
                            post(hapticRun);
                            if (tgt.hp <= 30 && !tgt.cried) { tgt.cried = true; sound.play(tgt.hero.voice + "_wounded"); }
                        }
                        boolean allDeadB2 = true;
                        for (Player p : party) if (p.hp > 0) allDeadB2 = false;
                        if (allDeadB2) { deadT = 2f; sound.play(tgt.hero.voice + "_death"); }
                    } else if (active.beast && active.beastForm == 3) {
                        // Bite
                        sound.play("swing");
                        shakeT = Math.max(shakeT, 0.1f);
                        spawnSlash(tgt.x, tgt.y);
                        int dmg = Enemy.BEAST_ATK_DMG[2];
                        if (!storyTest) tgt.hp -= dmg;
                        hurtT = 0.3f;
                        addDmg(tgt.x, tgt.y - PLAYER_H - 20, -dmg);
                        sound.play("hit");
                        if (tgt.hp > 0) {
                            sound.play("hurt");
                            sound.play(tgt.hero.voice + "_hurt");
                            post(hapticRun);
                            if (tgt.hp <= 30 && !tgt.cried) { tgt.cried = true; sound.play(tgt.hero.voice + "_wounded"); }
                        }
                        boolean allDeadB3 = true;
                        for (Player p : party) if (p.hp > 0) allDeadB3 = false;
                        if (allDeadB3) { deadT = 2f; sound.play(tgt.hero.voice + "_death"); }
                    } else if (active.heavy && active.atkForm == 2) {
                        // Nova: hits all heroes within 2 hexes.
                        active.mana -= Enemy.HEAVY_ATK_MANA[1];
                        sound.play("blast");
                        spawnBlast(active.x, active.y);
                        shakeT = 0.22f;
                        zoomPunch = 0.12f;
                        worldToHex(active.x, active.y, IH_A);
                        for (Player p : party) {
                            if (p.hp <= 0) continue;
                            worldToHex(p.x, p.y, IH_B);
                            if (hexDist(IH_A[0], IH_A[1], IH_B[0], IH_B[1]) <= Enemy.HEAVY_ATK_RANGE[1]) {
                                spawnSlash(p.x, p.y);
                                if (!storyTest) p.hp -= Enemy.HEAVY_ATK_DMG[1];
                                addDmg(p.x, p.y - PLAYER_H - 20, -Enemy.HEAVY_ATK_DMG[1]);
                                hurtT = 0.4f;
                                sound.play("hit");
                                if (p.hp > 0) {
                                    sound.play("hurt");
                                    sound.play(p.hero.voice + "_hurt");
                                    post(hapticRun);
                                    if (p.hp <= 30 && !p.cried) {
                                        p.cried = true;
                                        sound.play(p.hero.voice + "_wounded");
                                    }
                                }
                            }
                        }
                        boolean allDead = true;
                        for (Player p : party) if (p.hp > 0) allDead = false;
                        if (allDead) {
                            deadT = 2f;
                            sound.play(player.hero.voice + "_death");
                        }
                    } else if (adj) {
                        // Single-target (light enemy, or heavy blade combo).
                        sound.play(active.weapon == 1 ? "claw" : "swing");
                        int dmg = active.heavy ? Enemy.HEAVY_ATK_DMG[0] : 10;
                        if (!storyTest) tgt.hp -= dmg;
                        hurtT = 0.3f;
                        addDmg(tgt.x, tgt.y - PLAYER_H - 20, -dmg);
                        sound.play("hit");
                        if (tgt.hp > 0) {
                            sound.play("hurt");
                            sound.play(tgt.hero.voice + "_hurt");
                            post(hapticRun);
                            if (tgt.hp <= 30 && !tgt.cried) {
                                tgt.cried = true;
                                sound.play(tgt.hero.voice + "_wounded");
                            }
                        }
                        boolean allDead = true;
                        for (Player p : party) if (p.hp > 0) allDead = false;
                        if (allDead) {
                            deadT = 2f;
                            sound.play(tgt.hero.voice + "_death");
                        }
                    }
                }
                if (active.act == 3) active.acted = true;
            } else {
                startPlayerTurn();
            }
        }

        if (player.hero.airborne() && player.isMoving()) {
            puffTimer += dt;
            if (puffTimer > 0.09f) {
                puffTimer = 0;
                spawnPuff(player.x + (float) (Math.random() * 36 - 18),
                          player.y + (float) (Math.random() * 10 - 5));
            }
        }
        for (Puff p : puffPool) {
            if (!p.active) continue;
            p.t += dt;
            if (p.t > 0.5f) p.active = false;
        }
        for (Dmg d : dmgPool) {
            if (!d.active) continue;
            d.t += dt;
            if (d.t > 0.8f) d.active = false;
        }
        if (storyTest) {
            for (Player p : party) { p.hp = Math.max(p.hp, 1); p.cried = false; }
        }
        boolean heroMoving = false;
        for (Player p : party) if (p.isMoving()) { heroMoving = true; break; }
        boolean enemyMoving = false;
        for (Enemy en : enemies) if (!en.dead && en.floater.moving) { enemyMoving = true; break; }
        sound.setFootstepsLight(heroMoving);
        sound.setFootstepsHeavy(enemyMoving);
    }
    private void draw() {
        SurfaceHolder h = getHolder();
        if (!h.getSurface().isValid()) return;
        Canvas cv;
        if (Build.VERSION.SDK_INT >= 26) cv = h.lockHardwareCanvas();
        else cv = h.lockCanvas();
        if (cv == null) return;

        W = cv.getWidth(); H = cv.getHeight();
        if (state == STATE_MENU) drawMenu(cv);
        else if (state == STATE_SELECT) drawSelect(cv);
        else if (state == STATE_CHAPTER) drawChapter(cv);
        else drawGame(cv);
        h.unlockCanvasAndPost(cv);
    }

    private float sx(float wx) { return (wx - camX + shakeX) * (zoom + zoomPunch) + W / 2f; }
    private float sy(float wy) { return (wy - camY + shakeY) * (zoom + zoomPunch) + H / 2f; }

    private Shader titleShader;
    private int titleShaderW = -1, titleShaderH = -1;

    private void drawMenu(Canvas cv) {
        if (menuBmp != null) { rf.set(0, 0, W, H); cv.drawBitmap(menuBmp, null, rf, paint); }
        else cv.drawColor(C_INK);
        if (menuBg != null) {
            paint.setAlpha(110);
            rf.set(0, 0, W, H); cv.drawBitmap(menuBg, null, rf, paint);
            paint.setAlpha(255);
        }
        if (keyBmp != null) {
            float ks = 46 + (float) Math.sin(loadT * 2.2f) * 3;
            float ky = H * 0.30f - Math.min(W * 0.16f, 170) * 1.05f;
            rf.set(W / 2f - ks / 2, ky - ks, W / 2f + ks / 2, ky);
            cv.drawBitmap(keyBmp, null, rf, paint);
        }

        if (quality > 0) {
            paint.setColor(C_EMBER);
            for (Ember em : embers) {
                paint.setAlpha((int) (40 + em.s));
                cv.drawCircle(em.x, em.y, 1.5f + em.s / 40f, paint);
            }
            paint.setAlpha(255);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        float ts = Math.min(W * 0.16f, 170);
        paint.setTextSize(ts);
        paint.setFakeBoldText(true);
        paint.setTypeface(fLogo);
        if (titleShader == null || titleShaderW != W || titleShaderH != H) {
            titleShaderW = W; titleShaderH = H;
            titleShader = new LinearGradient(0, H * 0.30f - ts, 0, H * 0.30f + ts * 0.3f,
                    new int[] { 0xFFffffff, C_BONE, 0xFFd9c6bb, C_BRIGHT },
                    new float[] { 0f, 0.42f, 0.68f, 1f }, Shader.TileMode.CLAMP);
        }
        paint.setShader(titleShader);
        cv.drawText("DOWN", W / 2f, H * 0.30f, paint);
        paint.setShader(null);
        paint.setFakeBoldText(false);
        paint.setTypeface(fSerif);
        paint.setTextSize(Math.min(W * 0.028f, 26));
        paint.setColor(0x88b7a6ab);
        cv.drawText("a hex descent", W / 2f, H * 0.30f + ts * 0.42f, paint);

        float bw = Math.min(W * 0.55f, 540), bh = Math.min(H * 0.13f, 92);
        float gap = Math.max(24, H * 0.045f);
        menuBtnTest.set(W / 2f - bw / 2, H * 0.52f, W / 2f + bw / 2, H * 0.52f + bh);
        menuBtnStory.set(W / 2f - bw / 2, H * 0.52f + bh + gap,
                W / 2f + bw / 2, H * 0.52f + bh * 2 + gap);
        drawMenuButton(cv, menuBtnTest, "STORY - TEST", C_MAGENTA,
                menuPress == 1, assetsReady);
        drawMenuButton(cv, menuBtnStory, "STORY - PLAY", 0xFF7d78a0,
                menuPress == 2, assetsReady);

        if (!assetsReady) drawLoader(cv);

        if (overlay != null) { rf.set(0, 0, W, H); cv.drawBitmap(overlay, null, rf, paint); }

        paint.setTypeface(fBody);
        paint.setTextSize(22);
        paint.setColor(0x55ffffff);
        cv.drawText("v0.1", 24, H - 24, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(0x66b7a6ab);
        cv.drawText("† Nilouverse", W - 24, H - 24, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLoader(Canvas cv) {
        paint.setColor(0xAA000000);
        cv.drawRect(0, 0, W, H, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fSerif);
        paint.setTextSize(30);
        paint.setColor(C_BONE_DIM);
        String line = LOAD_LINES[((int) (loadT * 1.2f)) % LOAD_LINES.length];
        cv.drawText(line, W / 2f, H * 0.62f, paint);
        float lw = Math.min(W * 0.4f, 300);
        rf.set(W / 2f - lw / 2, H * 0.68f, W / 2f + lw / 2, H * 0.68f + 4);
        paint.setColor(0x33efe6dd);
        cv.drawRect(rf, paint);
        float k = (loadT % 1.4f) / 1.4f;
        rf.set(W / 2f - lw / 2 + (lw - 60) * k, H * 0.68f,
               W / 2f - lw / 2 + (lw - 60) * k + 60, H * 0.68f + 4);
        paint.setColor(C_MAGENTA);
        cv.drawRect(rf, paint);
    }

    private void cutRect(RectF r, float c) {
        btnPath.reset();
        btnPath.moveTo(r.left + c, r.top);
        btnPath.lineTo(r.right, r.top);
        btnPath.lineTo(r.right, r.bottom - c);
        btnPath.lineTo(r.right - c, r.bottom);
        btnPath.lineTo(r.left, r.bottom);
        btnPath.lineTo(r.left, r.top + c);
        btnPath.close();
    }

    private void drawMenuButton(Canvas cv, RectF r, String label, int accent,
                                boolean pressed, boolean enabled) {
        cutRect(r, 12);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0x50301f4a : 0x2A1c1230);
        cv.drawPath(btnPath, paint);
        if (pressed) {
            cv.save();
            cv.clipPath(btnPath);
            paint.setColor(0x22ffffff);
            cv.drawRect(r.centerX() - 20, r.top, r.centerX() + 20, r.bottom, paint);
            cv.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(pressed ? 4f : 2.5f);
        paint.setColor(enabled ? accent : 0xFF55506e);
        cv.drawPath(btnPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? C_BONE : 0xFF9a94b8);
        paint.setTypeface(fBody);
        paint.setTextSize(Math.min(r.height() * 0.38f, 40));
        cv.drawText(label, r.centerX(), r.centerY() + paint.getTextSize() * 0.35f, paint);
        paint.setStrokeWidth(0);
    }

    private boolean onMenuTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (!assetsReady) return true;
        if (act == MotionEvent.ACTION_DOWN) {
            menuPress = 0;
            if (menuBtnTest.contains(e.getX(), e.getY())) menuPress = 1;
            else if (menuBtnStory.contains(e.getX(), e.getY())) menuPress = 2;
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            if (menuPress == 1 && menuBtnTest.contains(e.getX(), e.getY())) {
                sound.play("ui");
                storyTest = true;
                startStory();
            }
            if (menuPress == 2 && menuBtnStory.contains(e.getX(), e.getY())) {
                sound.play("ui");
                storyTest = false;
                startStory();
            }
            menuPress = 0;
            return true;
        }
        return true;
    }

    private boolean onSelectTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (!assetsReady) return true;
        if (act == MotionEvent.ACTION_DOWN) {
            menuPress = 0;
            if (selBtn0.contains(e.getX(), e.getY())) menuPress = 3;
            else if (selBtn1.contains(e.getX(), e.getY())) menuPress = 4;
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            if (menuPress == 3 && selBtn0.contains(e.getX(), e.getY())) {
                sound.play("ui");
                sound.play(roster[0].voice + "_select");
                startGame(roster[0]);
            }
            if (menuPress == 4 && selBtn1.contains(e.getX(), e.getY())) {
                sound.play("ui");
                sound.play(roster[1].voice + "_select");
                startGame(roster[1]);
            }
            menuPress = 0;
            return true;
        }
        return true;
    }

    private void drawSelect(Canvas cv) {
        if (menuBmp != null) { rf.set(0, 0, W, H); cv.drawBitmap(menuBmp, null, rf, paint); }
        if (menuBg != null) {
            paint.setAlpha(110);
            rf.set(0, 0, W, H); cv.drawBitmap(menuBg, null, rf, paint);
            paint.setAlpha(255);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(fLogo);
        paint.setTextSize(64);
        paint.setColor(C_BONE);
        cv.drawText("DOWN", W / 2f, H * 0.26f, paint);
        float bw = Math.min(W * 0.38f, 420), bh = Math.min(H * 0.16f, 110);
        selBtn0.set(W / 2f - bw - 20, H * 0.45f, W / 2f - 20, H * 0.45f + bh);
        selBtn1.set(W / 2f + 20, H * 0.45f, W / 2f + bw + 20, H * 0.45f + bh);
        drawMenuButton(cv, selBtn0, roster[0].name, C_MAGENTA, menuPress == 3, true);
        drawMenuButton(cv, selBtn1, roster[1].name, C_CYAN, menuPress == 4, true);
        if (overlay != null) { rf.set(0, 0, W, H); cv.drawBitmap(overlay, null, rf, paint); }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGround(Canvas cv) {
        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        float wx0 = camX - halfW, wx1 = camX + halfW;
        float wy0 = camY - halfH, wy1 = camY + halfH;

        paint.setAlpha(255);
        paint.setColor(storyMode && story != null ? story.groundColor : 0xFF150d16);
        cv.drawRect(0, 0, W, H, paint);

        float strip = 30f;
        float x = ((float) Math.floor(wx0 / strip) - 1) * strip;
        for (; x < wx1 + strip; x += strip) {
            float tx = x / TILE;
            float cyw = roadCenterF(tx) * TH;
            float half = TILE * (0.80f + 0.10f * (float) Math.sin(tx * 0.21f + 0.9f)) * SQUASH;
            float sxp = sx(x), w = strip * zoom + 2f;
            float top = sy(cyw - half), bot = sy(cyw + half);

            paint.setColor(0xFF0d0710);
            rf.set(sxp - 1, top - 9 * zoom, sxp + w + 1, top + 2 * zoom);
            cv.drawRect(rf, paint);
            rf.set(sxp - 1, bot - 2 * zoom, sxp + w + 1, bot + 9 * zoom);
            cv.drawRect(rf, paint);

            paint.setColor(0xFF2a1c2c);
            rf.set(sxp, top, sxp + w, bot);
            cv.drawRect(rf, paint);

            paint.setColor(0xFF3a2438);
            rf.set(sxp, top + 2 * zoom, sxp + w, top + 5 * zoom);
            cv.drawRect(rf, paint);
            rf.set(sxp, bot - 5 * zoom, sxp + w, bot - 2 * zoom);
            cv.drawRect(rf, paint);

            int idx = (int) Math.floor(x / (TILE * 0.9f));
            if ((idx & 1) == 0) {
                paint.setColor(0x55120b16);
                float my = sy(cyw);
                rf.set(sxp + 3 * zoom, my - 1.5f * zoom, sxp + w - 3 * zoom, my + 1.5f * zoom);
                cv.drawRect(rf, paint);
            }
        }

        int px0 = (int) Math.floor(wx0 / (TILE * 2)) - 1, px1 = (int) Math.ceil(wx1 / (TILE * 2)) + 1;
        int py0 = (int) Math.floor(wy0 / (TH * 2)) - 1, py1 = (int) Math.ceil(wy1 / (TH * 2)) + 1;
        for (int py = py0; py <= py1; py++) {
            for (int px = px0; px <= px1; px++) {
                int h = (px * 40503) ^ (py * 66827);
                if (((h >>> 4) & 7) != 0) continue;
                float cxw = px * TILE * 2 + TILE * (0.3f + ((h >>> 7) & 127) / 127f * 1.4f);
                float cyw = py * TH * 2 + TH * (0.3f + ((h >>> 11) & 31) / 127f * 1.4f);
                if (Math.abs(cyw / TH - roadCenterF(cxw / TILE)) < 1.0f) continue;
                float rw = (50 + ((h >>> 15) & 63)) * zoom;
                float rh = rw * SQUASH * (0.6f + ((h >>> 21) & 31) / 31f * 0.5f);
                paint.setColor(0xFF120a14);
                rf.set(sx(cxw) - rw, sy(cyw) - rh, sx(cxw) + rw, sy(cyw) + rh);
                cv.drawOval(rf, paint);
            }
        }

        int ty0 = (int) Math.floor(wy0 / TH) - 1, ty1 = (int) Math.ceil(wy1 / TH) + 1;
        paint.setColor(0x12000000);
        for (int ty = ty0; ty <= ty1 + 1; ty++) {
            float y = sy(ty * TH);
            rf.set(-2, y - zoom, W + 2, y + 1.6f * zoom);
            cv.drawRect(rf, paint);
        }
    }

    private D obtainD() {
        return dPool.isEmpty() ? new D() : dPool.remove(dPool.size() - 1);
    }

    private void drawSorted(Canvas cv) {
        for (int i = 0; i < drawList.size(); i++) dPool.add(drawList.get(i));
        drawList.clear();

        float halfW = W / (2f * zoom), halfH = H / (2f * zoom);
        int tx0 = (int) Math.floor((camX - halfW) / TILE) - 1;
        int tx1 = (int) Math.ceil ((camX + halfW) / TILE) + 1;
        int ty0 = (int) Math.floor((camY - halfH) / TH) - 1;
        int ty1 = (int) Math.ceil ((camY + halfH) / TH) + 1;

        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (storyMode) continue;
                int h = (tx * 40503) ^ (ty * 66827);
                int roll = (h >>> 3) % 100;
                if (roll < 8 && !props2.isEmpty()) {
                    D d = obtainD();
                    d.kind = 0;
                    d.pr = props2.get((h >>> 5) % props2.size());
                    d.ax = tx * TILE + TILE * (0.3f + ((h >>> 9) & 127) / 127f * 0.4f);
                    d.ay = (ty + 1) * TH - TH * 0.10f;
                    d.s = (TH * 2.43f / d.pr.getHeight())
                            * (0.85f + ((h >>> 13) & 31) / 31f * 0.45f);
                    d.y = d.ay;
                    drawList.add(d);
                } else if (roll < 22 && !props.isEmpty()) {
                    D d = obtainD();
                    d.kind = 0;
                    d.pr = props.get((h >>> 5) % props.size());
                    d.ax = tx * TILE + TILE * (0.25f + ((h >>> 9) & 127) / 127f * 0.5f);
                    d.ay = (ty + 1) * TH - TH * (0.15f + ((h >>> 11) & 31) / 31f * 0.25f);
                    d.s = (TH * 0.45f / d.pr.getHeight())
                            * (0.8f + ((h >>> 15) & 31) / 31f * 0.5f);
                    d.y = d.ay;
                    drawList.add(d);
                }
            }
        }

        if (storyMode) addPlacedProps();
        for (Player pp : party) { D p = obtainD(); p.kind = 1; p.pl = pp; p.y = pp.y; drawList.add(p); }
        for (Enemy en : enemies) { D d = obtainD(); d.kind = 2; d.en = en; d.y = en.y; drawList.add(d); }

        Collections.sort(drawList, BY_Y);
        for (D d : drawList) {
            if (d.kind == 0) drawProp(cv, d);
            else if (d.kind == 1) drawPlayer(cv, d.pl);
            else drawEnemy(cv, d.en);
        }
    }

    private void drawProp(Canvas cv, D d) {
        float s = d.s * zoom;
        cv.save();
        cv.translate(sx(d.ax), sy(d.ay));
        if (d.obj == Boolean.TRUE) cv.scale(-1f, 1f);
        rf.set(-d.pr.getWidth() * s / 2f, -d.pr.getHeight() * s,
               d.pr.getWidth() * s / 2f, 0);
        paint.setAlpha(255);
        cv.drawBitmap(d.pr, null, rf, paint);
        cv.restore();
    }

    private List<Bitmap> propList(int sheet, boolean flat) {
        if (sheet == 0) return flat ? propsAF : props;
        if (sheet == 1) return flat ? propsBF : props2;
        if (sheet == 3) return propsGate;
        return flat ? propsCF : propsCity;
    }

    private void addPlacedProps() {
        StoryWorld sw = sw();
        for (int i = 0; i < sw.placedProps.size(); i++) {
            StoryWorld.Prop pr = sw.placedProps.get(i);
            if (pr.flat) continue;
            List<Bitmap> list = propList(pr.sheet, false);
            int idx = pr.idx;
            if (pr.sheet == 3 && (list == null || list.isEmpty())) { list = propsCity; idx = 0; }
            if (list == null || list.isEmpty()) continue;
            Bitmap b = list.get(idx % list.size());
            if (b == null || b.isRecycled()) continue;
            float px = sx(pr.x), py = sy(pr.y);
            if (px < -300 || px > W + 300 || py < -300 || py > H + 300) continue;
            D d = obtainD();
            d.kind = 0;
            d.pr = b;
            d.ax = pr.x; d.ay = pr.y;
            float hh = TH * 1.9f * pr.scale;
            if (pr.sheet == 3) {
                // G2: gate doubled; scale = nudge knob.
                hh = 12f * (HEX * 1.5f * SQUASH) * pr.scale;
                // base sits six rows south so the arch straddles the path line.
                d.ay = pr.y + 6f * (HEX * 1.5f * SQUASH);
            }
            d.s = hh / b.getHeight();
            d.y = d.ay;
            d.obj = pr.flip ? Boolean.TRUE : null;
            drawList.add(d);
        }
    }

    private void drawFlatProps(Canvas cv) {
        StoryWorld sw = sw();
        for (int i = 0; i < sw.placedProps.size(); i++) {
            StoryWorld.Prop pr = sw.placedProps.get(i);
            if (!pr.flat) continue;
            List<Bitmap> list = propList(pr.sheet, true);
            if (list == null || list.isEmpty()) continue;
            Bitmap b = list.get(pr.idx % list.size());
            if (b == null || b.isRecycled()) continue;
            float px = sx(pr.x), py = sy(pr.y);
            if (px < -400 || px > W + 400 || py < -400 || py > H + 400) continue;
            float w = HEX * 2.2f * pr.scale * zoom;
            float h = w * b.getHeight() / (float) b.getWidth();
            cv.save();
            cv.translate(px, py);
            if (pr.flip) cv.scale(-1f, 1f);
            rf.set(-w / 2f, -h / 2f, w / 2f, h / 2f);
            paint.setAlpha(235);
            cv.drawBitmap(b, null, rf, paint);
            paint.setAlpha(255);
            cv.restore();
        }
        if (sw.scatterSet) drawScatter(cv, sw);
    }

    private void drawScatter(Canvas cv, StoryWorld sw) {
        for (int i = 0; i < sw.scatterN; i++) {
            int h = h2(sw.scatterQ * 31 + i, sw.scatterR * 17 + i, 5);
            int dq = ((h & 255) % (sw.scatterRad * 2 + 1)) - sw.scatterRad;
            int dr = (((h >>> 8) & 255) % (sw.scatterRad * 2 + 1)) - sw.scatterRad;
            SceneMap.hexToWorld(sw.scatterQ + dq, sw.scatterR + dr, FW_A);
            float px = sx(FW_A[0]), py = sy(FW_A[1]);
            if (px < -400 || px > W + 400 || py < -400 || py > H + 400) continue;
            int pick = (h >>> 16) % 3;
            Bitmap b = pick == 0 ? safeGet(propsAF, 4) : (pick == 1 ? safeGet(propsAF, 5) : safeGet(propsCF, 7));
            if (b == null) continue;
            float w = HEX * (1.4f + ((h >>> 20) & 63) / 63f * 0.9f) * zoom;
            float hh = w * b.getHeight() / (float) b.getWidth();
            cv.save();
            cv.translate(px, py);
            if (((h >>> 12) & 1) == 1) cv.scale(-1f, 1f);
            rf.set(-w / 2f, -hh / 2f, w / 2f, hh / 2f);
            paint.setAlpha(225);
            cv.drawBitmap(b, null, rf, paint);
            paint.setAlpha(255);
            cv.restore();
        }
    }

    private Bitmap safeGet(List<Bitmap> l, int i) {
        return (l == null || l.isEmpty()) ? null : l.get(i % l.size());
    }

    private Player nearestAlive(float x, float y) {
        Player best = null; float bd = 1e18f;
        for (Player p : party) {
            if (p.hp <= 0) continue;
            float d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private void spawnBite(float x, float y) {
        int i = biteSlot++ & 3;
        biteX[i] = x; biteY[i] = y; biteT0[i] = loadT; biteOn[i] = true;
    }

    // Jaw-snap: two fang arcs closing fast, impact flash at the snap.
    private void drawBites(Canvas cv) {
        for (int i = 0; i < 4; i++) {
            if (!biteOn[i]) continue;
            float age = loadT - biteT0[i];
            if (age > 0.3f || age < 0) { biteOn[i] = false; continue; }
            float k = age / 0.3f;
            float x = sx(biteX[i]), y = sy(biteY[i]) - PLAYER_H * 0.5f * zoom;
            float open = (1f - k) * 26f * zoom + 4f * zoom;
            paint.setAlpha((int) (255 * (1f - k * 0.6f)));
            paint.setColor(0xfff2f6f0);
            paint.setStrokeWidth(5f * zoom);
            paint.setStyle(Paint.Style.STROKE);
            cv.drawArc(x - 20f * zoom, y - open - 18f * zoom, x + 20f * zoom, y - open + 18f * zoom, 20, 140, false, paint);
            cv.drawArc(x - 20f * zoom, y + open - 18f * zoom, x + 20f * zoom, y + open + 18f * zoom, 200, 140, false, paint);
            paint.setStyle(Paint.Style.FILL);
            if (k > 0.75f) {
                paint.setColor(0xffe8ffb0);
                paint.setAlpha((int) ((k - 0.75f) / 0.25f * 200));
                cv.drawCircle(x, y, 14f * zoom, paint);
            }
            paint.setAlpha(255);
        }
    }

    // Some sheets ship with a DARK magenta key that the standard keyer only
    // half-removes. Hard-scrub frame pools that are props-in-disguise.
    private static void scrubFrames(List<Frame> l) {
        if (l == null) return;
        for (int i = 0; i < l.size(); i++) {
            Frame f = l.get(i);
            if (f == null || f.bmp == null || f.bmp.isRecycled()) continue;
            Bitmap b = f.bmp.copy(Bitmap.Config.ARGB_8888, true);
            if (b == null) continue;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int p = 0; p < px.length; p++) {
                int c = px[p];
                int a = (c >>> 24) & 255;
                if (a == 0) continue;
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                int ex = (r < bl ? r : bl) - g;
                int lum = r + g + bl;
                if (ex > 60 || (ex > 25 && lum < 480)) px[p] = 0;
                else if (ex > 25) px[p] = (c & 0x00FFFFFF) | ((a / 2) << 24);
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
            f.bmp = b;
        }
    }

    // Universal story-actor sheet binding: heroes first, then every enemy kind.
    private Frame[] idlePoolFor(String type) {
        Frame[] f = heroSheets.get(type);
        if (f != null) return f;
        if ("soldier".equals(type)) return arrSoldierIdle;
        if ("sabrina".equals(type)) return arrSabrinaIdle;
        if ("infantry".equals(type)) return arrInfIdle;
        if ("beast".equals(type)) return arrBeastIdle;
        if ("enemy".equals(type)) return arrEnIdle;
        return null;
    }
    private Frame[] glidePoolFor(String type) {
        Frame[] g = heroGlide.get(type);
        if (g != null) return g;
        if ("soldier".equals(type)) return arrSoldierGlide;
        if ("sabrina".equals(type)) return arrSabrinaGlide;
        if ("infantry".equals(type)) return arrInfGlide;
        if ("beast".equals(type)) return arrBeastGlide;
        if ("enemy".equals(type)) return arrEnGlide;
        return null;
    }

    // Kills the magenta fringe on keyed props without touching character sheets.
    private static void scrubFringe(List<Bitmap> l) {
        if (l == null) return;
        for (int i = 0; i < l.size(); i++) {
            Bitmap src = l.get(i);
            if (src == null || src.isRecycled()) continue;
            Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
            if (b == null) continue;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int p = 0; p < px.length; p++) {
                int c = px[p];
                int a = (c >>> 24) & 255;
                if (a == 0) continue;
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                int ex = (r < bl ? r : bl) - g;
                if (ex > 60) px[p] = 0;
                else if (ex > 30) px[p] = (c & 0x00FFFFFF) | ((a / 2) << 24);
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
            l.set(i, b);
        }
    }

    private static int h2(int x, int y, int s) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ s * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    // Beast leap attack: jump onto the target, land, hop one hex back.
    private void beginBeastLeap(Enemy en, Player tgt) {
        float[] d = new float[9];
        d[0] = en.x; d[1] = en.y;
        d[2] = tgt.x; d[3] = tgt.y;
        worldToHex(en.x, en.y, IH_B);
        worldToHex(tgt.x, tgt.y, IH_A);
        int dq = IH_B[0] - IH_A[0], dr = IH_B[1] - IH_A[1];
        // F3: hop back ONE hex from the TARGET, away toward the beast's side
        int rq = IH_A[0] + (dq > 0 ? 1 : (dq < 0 ? -1 : 0));
        int rr = IH_A[1] + (dr > 0 ? 1 : (dr < 0 ? -1 : 0));
        if (hexFree(rq, rr, en)) { hexToWorld(rq, rr, FW_A); d[4] = FW_A[0]; d[5] = FW_A[1]; }
        else { d[4] = tgt.x; d[5] = tgt.y; }
        leapOff.put(en, d);
    }

    private void stepBeastLeap(Enemy en, Player tgt) {
        float[] d = leapOff.get(en);
        if (d == null) return;
        float t = en.attackT;
        float dx = 0, dy = 0, lift = 0;
        if (t < 0.55f) {
            float k = t / 0.55f; float e = k * k * (3 - 2 * k);
            dx = (d[2] - d[0]) * e; dy = (d[3] - d[1]) * e;
            lift = (float) Math.sin(k * 3.14159f) * 120f;
        } else if (t < 0.85f) {
            if (en.x != d[2] || en.y != d[3]) { en.x = d[2]; en.y = d[3]; }
            dx = 0; dy = 0;
        } else {
            if (en.x != d[4] || en.y != d[5]) {
                en.x = d[4]; en.y = d[5];
                en.facing = d[2] >= d[4] ? 1 : -1;
            }
            float k = Math.min(1f, (t - 0.85f) / 0.75f); float e = k * k * (3 - 2 * k);
            dx = (d[2] - d[4]) * (1 - e); dy = (d[3] - d[5]) * (1 - e);
            lift = (float) Math.sin(k * 3.14159f) * 120f;
        }
        d[6] = dx; d[7] = dy; d[8] = lift;
    }

    private int palIdx(int col) {
        if (col == 0xAAefe6dd) return 0;
        if (col == 0xFFefe6dd) return 1;
        if (col == 0x14efe6dd) return 2;
        if (col == 0x22efe6dd) return 3;
        if (col == 0xFF34e3d6) return 4;
        return 0;
    }

    private void drawHex(Canvas cv, float cx, float cy, int color, boolean filled) {
        float hr = HEX * 1.1f * zoom;
        rf.set(cx - hr, cy - hr * SQUASH, cx + hr, cy + hr * SQUASH);
        if (hexTinted != null) {
            cv.drawBitmap(hexTinted[palIdx(color)], null, rf, paint);
        }
        if (filled) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * zoom);
            paint.setColor(0x44ffffff);
            cv.drawOval(rf, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0);
        }
    }

    private void drawHexRing(Canvas cv, float cx, float cy, int strokeCol, int fillCol) {
        float hr = HEX * 1.05f * zoom;
        hexPath.reset();
        for (int k = 0; k < 6; k++) {
            float x = cx + HEXU[k * 2] * hr;
            float y = cy + HEXU[k * 2 + 1] * hr;
            if (k == 0) hexPath.moveTo(x, y); else hexPath.lineTo(x, y);
        }
        hexPath.close();
        if (fillCol != 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillCol);
            cv.drawPath(hexPath, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * zoom);
        paint.setColor(strokeCol);
        cv.drawPath(hexPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0);
    }

    private void buildReach(int maxDist) {
        reachDist.clear(); reachParent.clear();
        worldToHex(player.x, player.y, IH_A);
        long start = hexKey(IH_A[0], IH_A[1]);
        reachDist.put(start, 0);
        ArrayDeque<long[]> q = new ArrayDeque<>();
        q.addLast(new long[]{IH_A[0], IH_A[1]});
        while (!q.isEmpty()) {
            long[] c = q.pollFirst();
            int cq = (int) c[0], cr = (int) c[1];
            int d = reachDist.get(hexKey(cq, cr));
            if (d >= maxDist) continue;
            for (int[] n : NEIGH6) {
                int nq = cq + n[0], nr = cr + n[1];
                long k = hexKey(nq, nr);
                if (reachDist.containsKey(k)) continue;
                if (!hexFree(nq, nr, null)) continue;
                reachDist.put(k, d + 1);
                reachParent.put(k, hexKey(cq, cr));
                q.addLast(new long[]{nq, nr});
            }
        }
    }

    private void rebuildFan() {
        worldToHex(player.x, player.y, IH_A);
        fanQ = IH_A[0]; fanR = IH_A[1];
        fanMoveMax = player.hero.moveMax;
        fanN = 0; fan2N = 0;
        buildReach(fanMoveMax * 2);
        for (java.util.Map.Entry<Long, Integer> e : reachDist.entrySet()) {
            long k = e.getKey(); int d = e.getValue();
            if (d < 1) continue;
            int q = (int) (k >> 32), r = (int) (k & 0xFFFFFFFFL);
            if (d <= fanMoveMax) { if (fanN < fanQs.length) { fanQs[fanN] = q; fanRs[fanN] = r; fanN++; } }
            else { if (fan2N < fan2Qs.length) { fan2Qs[fan2N] = q; fan2Rs[fan2N] = r; fan2N++; } }
        }
        fanDirty = false;
    }

    private boolean tryMoveTo(int q, int r) {
        if (player.actionsLeft <= 0) return false;
        buildReach(player.hero.moveMax * 2);
        worldToHex(player.x, player.y, IH_A);
        long startK = hexKey(IH_A[0], IH_A[1]);
        long tk = hexKey(q, r);
        Integer rd = reachDist.get(tk);
        if (rd == null || rd < 1) return false;
        int cost = rd <= player.hero.moveMax ? 1 : 2;
        if (player.actionsLeft < cost) return false;
        float[] xs = new float[rd]; float[] ys = new float[rd];
        long cur = tk; int idx = rd - 1;
        while (idx >= 0 && cur != startK) {
            int cq = (int) (cur >> 32), cr = (int) (cur & 0xFFFFFFFFL);
            hexToWorld(cq, cr, FW_A);
            xs[idx] = FW_A[0]; ys[idx] = FW_A[1];
            idx--;
            Long par = reachParent.get(cur);
            if (par == null) break;
            cur = par;
        }
        player.setPath(xs, ys, rd);
        player.actionsLeft -= cost;
        hexesShown = false;
        fanDirty = true;
        runeX = xs[rd - 1]; runeY = ys[rd - 1]; runeT = 0;
        sound.play("step");
        sound.play(voice + "_move");
        hapticTiered(0);
        return true;
    }

    private void drawMoveFan(Canvas cv) {
        if (player.hp <= 0 || player.actionsLeft <= 0 || player.isMoving()) return;
        worldToHex(player.x, player.y, IH_A);
        if (fanDirty || fanQ != IH_A[0] || fanR != IH_A[1] || fanMoveMax != player.hero.moveMax) {
            rebuildFan();
        }
        int baseAlpha = 170 + (int) (85 * (float) Math.sin(loadT * 6f));
        paint.setAlpha(baseAlpha);
        for (int i = 0; i < fanN; i++) {
            hexToWorld(fanQs[i], fanRs[i], FW_A);
            drawHexRing(cv, sx(FW_A[0]), sy(FW_A[1]), 0xAAefe6dd, 0x14efe6dd);
        }
        if (player.actionsLeft >= 2) {
            paint.setAlpha(160);
            for (int i = 0; i < fan2N; i++) {
                hexToWorld(fan2Qs[i], fan2Rs[i], FW_A);
                drawHexRing(cv, sx(FW_A[0]), sy(FW_A[1]), 0x9939ff14, 0x1839ff14);
            }
        }
        paint.setAlpha(255);
    }

    private void drawAttackRange(Canvas cv) {
        worldToHex(player.x, player.y, IH_A);
        if (atkDirty || atkRangeQ != IH_A[0] || atkRangeR != IH_A[1]
                || atkRangeR2 != player.hero.attacks[attackRangeShown - 1].range) {
            rebuildAtk();
        }
        int baseAlpha = 170 + (int) (85 * (float) Math.sin(loadT * 6f));
        paint.setAlpha(baseAlpha);
        for (int i = 0; i < atkN; i++) {
            hexToWorld(atkQs[i], atkRs[i], FW_A);
            drawHexRing(cv, sx(FW_A[0]), sy(FW_A[1]), 0xAAefe6dd, 0x14efe6dd);
        }
        paint.setAlpha(255);
        if (atkRangeKind != 2) {
            for (Enemy en : enemies) {
                if (en.dead) continue;
                worldToHex(en.x, en.y, IH_B);
                if (hexDist(atkRangeQ, atkRangeR, IH_B[0], IH_B[1]) <= atkRangeR2) {
                    hexToWorld(IH_B[0], IH_B[1], FW_A);
                    drawHexRing(cv, sx(FW_A[0]), sy(FW_A[1]), 0xFFefe6dd, 0x22efe6dd);
                }
            }
        }
    }

    private void rebuildAtk() {
        atkRangeQ = IH_A[0];
        atkRangeR = IH_A[1];
        if (attackRangeShown < 1 || attackRangeShown > player.hero.attacks.length) {
            atkN = 0;
            atkDirty = false;
            return;
        }
        atkRangeR2 = player.hero.attacks[attackRangeShown - 1].range;
        atkN = 0;
        int R = (int) Math.ceil(atkRangeR2);
        for (int dq = -R; dq <= R; dq++) {
            int r1 = Math.max(-R, -dq - R);
            int r2 = Math.min(R, -dq + R);
            for (int dr = r1; dr <= r2; dr++) {
                if (atkN >= atkQs.length) break;
                atkQs[atkN] = atkRangeQ + dq;
                atkRs[atkN] = atkRangeR + dr;
                atkN++;
            }
        }
        atkDirty = false;
    }

    private void drawRune(Canvas cv) {
        if (runeT > 0.6f) return;
        float k = runeT / 0.6f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setColor(C_MAGENTA);
        paint.setAlpha((int) (255 * (1 - k)));
        float r = (20 + k * 50) * zoom;
        rf.set(sx(runeX) - r, sy(runeY) - r / 2f, sx(runeX) + r, sy(runeY) + r / 2f);
        cv.drawOval(rf, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawPuffs(Canvas cv) {
        for (Puff p : puffPool) {
            if (!p.active) continue;
            float k = p.t / 0.5f;
            paint.setColor(C_VIOLET);
            paint.setAlpha((int) (110 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y) - k * 26 * zoom, (12 + k * 46) * zoom, paint);
        }
        paint.setAlpha(255);
    }

    private void drawBlasts(Canvas cv) {
        for (Blast b : blastPool) {
            if (!b.active) continue;
            float k = b.t / 0.5f;
            float r = (40 + k * HEX * 3.6f) * zoom;
            rf.set(sx(b.x) - r, sy(b.y) - r * SQUASH, sx(b.x) + r, sy(b.y) + r * SQUASH);

            if (blastFilter == null) {
                blastFilter = new PorterDuffColorFilter(C_BRIGHT, PorterDuff.Mode.SRC_IN);
            }
            paint.setColorFilter(blastFilter);
            paint.setAlpha((int) (220 * (1 - k)));
            cv.drawBitmap(blastBmp, null, rf, paint);
            paint.setColorFilter(null);
            paint.setAlpha(255);
        }
    }

    private void drawParticles(Canvas cv) {
        for (Particle p : particlePool) {
            if (!p.active) continue;
            float k = p.t / p.life;
            paint.setColor(p.col);
            paint.setAlpha((int) (255 * (1 - k)));
            cv.drawCircle(sx(p.x), sy(p.y), (4 + k * 6) * zoom, paint);
        }
        paint.setAlpha(255);
    }

    private void drawFrame(Canvas cv, Frame f, int alpha) {
        float s = PLAYER_H * zoom / f.ref;
        paint.setAlpha(alpha);
        if (f.vCrop) {
            frameSrc.set(0, f.top, f.bmp.getWidth(), f.top + f.ch);
            rf.set(-f.bmp.getWidth() * s / 2f, -f.ch * s, f.bmp.getWidth() * s / 2f, 0);
        } else if (f.cCenter) {
            int wl = Math.max(0, f.rgt - f.ww);
            int wr = f.rgt;
            if (wl >= wr || f.top + f.ch > f.bmp.getHeight()) {
                frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
                rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                        f.bmp.getWidth() * s / 2f, 0);
            } else {
                frameSrc.set(wl, f.top, wr, f.top + f.ch);
                float right = f.ww * s / 2f;
                rf.set(right - (wr - wl) * s, -f.ch * s, right, 0);
            }
        } else {
            frameSrc.set(0, 0, f.bmp.getWidth(), f.bmp.getHeight());
            rf.set(-f.bmp.getWidth() * s / 2f, -f.bmp.getHeight() * s,
                    f.bmp.getWidth() * s / 2f, 0);
        }
        if (f.dx != 0) rf.offset(f.dx * zoom, 0);
        cv.drawBitmap(f.bmp, frameSrc, rf, paint);
        paint.setAlpha(255);
    }

    private void drawPlayer(Canvas cv, Player pl) {
        Hero h = pl.hero;
        boolean fl = h.airborne();
        boolean idle = h.mode == 0 && !pl.isAttacking();
        boolean sel = (pl == player);
        float br = idle ? (float) Math.sin(pl.bobTime * 1.7f) : 0f;

        float by = sy(pl.y + h.visualY) + FOOT_DROP * zoom;
        if (pl == player && swLift > 0f) by -= swLift * zoom;

        if (!h.hidden) {
            float sw = (fl ? 45 : 55) * zoom * (1f - 0.045f * br);
            if (pl == player && swLift > 0f) sw *= 0.7f;
            paint.setAlpha((pl == player && swLift > 0f) ? 120 : (fl ? 150 : 220));
            rf.set(sx(pl.x) - sw, by - sw * 0.36f,
                   sx(pl.x) + sw, by + sw * 0.36f);
            cv.drawBitmap(shadowBmp, null, rf, paint);
            paint.setAlpha(255);

            if (sel) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * zoom);
                paint.setColor(0x66efe6dd);
                rf.set(sx(pl.x) - 42 * zoom, by - 42 * zoom * SQUASH,
                       sx(pl.x) + 42 * zoom, by + 42 * zoom * SQUASH);
                cv.drawOval(rf, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
            }
        }

        cv.save();
        float lx = h.visualX * pl.facing;
        cv.translate(sx(pl.x + lx), by);
        if (pl.facing < 0) cv.scale(-1, 1);
        if (br != 0f) cv.scale(1f - 0.018f * br, 1f + 0.03f * br);
        if (!h.hidden) {
            if (h.frameA != null) drawFrame(cv, h.frameA, 255);
            if (h.frameB != null && h.frameK > 0.02f)
                drawFrame(cv, h.frameB, (int) (h.frameK * 255));
        }
        cv.restore();

        float top = by - PLAYER_H * zoom - 34;
        float bw = 90, bh = 8;
        rf.set(sx(pl.x) - bw/2, top, sx(pl.x) + bw/2, top + bh);
        paint.setColor(0xCC050508); cv.drawRoundRect(rf, 4, 4, paint);
        rf.right = rf.left + bw * (Math.max(0, pl.hp) / 100f);
        paint.setColor(C_BLOOD); cv.drawRoundRect(rf, 4, 4, paint);
        if (pl == player) {
            float mtop = top + bh + 3;
            rf.set(sx(pl.x) - bw/2, mtop, sx(pl.x) + bw/2, mtop + 5);
            paint.setColor(0xCC050508); cv.drawRoundRect(rf, 3, 3, paint);
            rf.right = rf.left + bw * (Math.max(0, mana) / 100f);
            paint.setColor(C_VIOLET); cv.drawRoundRect(rf, 3, 3, paint);
        }
    }

    private Frame pickEnemyFrame(Enemy en) {
        if (en.attacking()) {
            if (en.beast) {
                java.util.List<Frame> pool = en.beastForm == 1 ? eBeastGlideFr : eBeastAtkFr;
                if (pool.isEmpty()) return null;
                int i;
                if (en.beastForm == 1) {
                    float t = en.attackT;
                    if (t < 0.10f) i = 0;       // A1
                    else if (t < 0.25f) i = 4;  // B1
                    else if (t < 0.40f) i = 7;  // B4
                    else if (t < 0.55f) i = 2;  // A3
                    else if (t < 0.70f) i = 5;  // B2
                    else if (t < 0.95f) i = 6;  // B3 land
                    else if (t < 1.15f) i = 3;  // A4
                    else if (t < 1.30f) i = 0;  // A1
                    else i = (((int) (en.animT * 10f)) % 2 == 0) ? 1 : 5; // jump-back shuffle
                } else {
                    int[] seq = Enemy.BEAST_ATK_SEQ[en.beastForm - 1];
                    float fps = eAtkFr.isEmpty() ? 6f : (eAtkFr.size() / Enemy.ATK_DUR);
                    int p = (int) (en.attackT * fps);
                    if (p > seq.length - 1) p = seq.length - 1;
                    i = seq[p];
                }
                if (i >= pool.size()) i = pool.size() - 1;
                return pool.get(i);
            }
            java.util.List<Frame> pool = en.heavy ? eHeavyAtkFr : eAtkFr;
            if (pool.isEmpty()) return null;
            int i;
            if (en.heavy) {
                int form = en.atkForm < 1 ? 1 : en.atkForm;
                int[] seq = Enemy.HEAVY_ATK_SEQ[form - 1];
                float fps = eAtkFr.isEmpty() ? 6f : (eAtkFr.size() / Enemy.ATK_DUR);
                int p = (int) (en.attackT * fps);
                if (p > seq.length - 1) p = seq.length - 1;
                i = seq[p];
                if (i >= pool.size()) i = pool.size() - 1;
            } else {
                float pos = (en.attackT / Enemy.ATK_DUR) * pool.size();
                i = (int) pos;
                if (i < 0) i = 0;
                if (i >= pool.size()) i = pool.size() - 1;
            }
            return pool.get(i);
        }
        if (en.floater.state == 0) {
            if (en.beast && !eBeastIdleFr.isEmpty()) {
                return eBeastIdleFr.get(((int) (en.animT * 3f)) % eBeastIdleFr.size());
            }
            java.util.List<Frame> pool = en.heavy ? eHeavyIdleFr : eIdleFr;
            if (!pool.isEmpty()) return pool.get(((int) (en.animT * 3f)) % pool.size());
        }
        if (en.beast && !eBeastGlideFr.isEmpty()) {
            Floater f = en.floater;
            int bn = eBeastGlideFr.size();
            if (f.state == 2) {
                // F2: full 6-frame run cycle (frames 2..7), Floater's intended mapping
                return eBeastGlideFr.get((2 + ((int) (en.animT * 8f)) % 6) % bn);
            }
            if (f.state == 1 || f.state == 3) return eBeastGlideFr.get(1 % bn);
            return eBeastGlideFr.get(0);
        }
        java.util.List<Frame> gPool = en.heavy ? eHeavyGlideFr : eGlideFr;
        if (gPool.size() >= 4) {
            Floater f = en.floater;
            if (f.state == 1) return gPool.get(f.t < 0.1f ? 3 : 1);
            if (f.state == 2) {
                int i = 1 + ((int) (en.animT * 6f)) % 2;
                return gPool.get(i);
            }
            return gPool.get(f.t < 0.06f ? 2 : f.t < 0.12f ? 1 : f.t < 0.17f ? 3 : 0);
        }
        return null;
    }

    private int enemyGroup(Enemy en) {
        if (en.attacking()) {
            int base = en.beast ? 50 : (en.heavy ? 30 : 10);
            return base + en.atkForm;
        }
        if (en.floater.state == 0) return en.beast ? 40 : (en.heavy ? 20 : 0);
        return (en.beast ? 41 : (en.heavy ? 21 : 1)) + en.floater.state;
    }

    private void drawEnemy(Canvas cv, Enemy en) {
        float bs = en.beast ? 1.3f : 1f;
        float[] lo = leapOff.get(en);
        float lx = lo != null ? lo[6] : 0f;
        float ly = lo != null ? lo[7] : 0f;
        float ll = lo != null ? lo[8] : 0f;
        float x = sx(en.x + lx), y = sy(en.y + ly + en.floater.visualY) + FOOT_DROP * zoom - ll * zoom;
        boolean idle = en.floater.state == 0 && !en.attacking() && !en.dead;
        float br = idle ? (float) Math.sin(en.animT * 1.7f) : 0f;
        float sw = 45 * zoom * bs * (1f - 0.045f * br);
        paint.setAlpha(220);
        rf.set(x - sw, y - sw * 0.36f - 18f * zoom, x + sw, y + sw * 0.36f - 18f * zoom);
        cv.drawBitmap(shadowBmp, null, rf, paint);
        paint.setAlpha(255);

        // D1: seamless crossfade via enemy's present()
        en.present(pickEnemyFrame(en), enemyGroup(en), 1f/60f);

        Paint p = (en.hitFlash > 0) ? tintPaint : paint;
        cv.save();
        cv.translate(x, y);
        if (en.facing < 0) cv.scale(-1, 1);
        if (bs != 1f) cv.scale(bs, bs);
        if (br != 0f) cv.scale(1f - 0.018f * br, 1f + 0.03f * br);
        // E4: death squash-then-sink
        if (en.dead) {
            float dk = en.deathT / 0.7f;
            float sinkY = dk * 28f * zoom;
            float sqY = 1f - dk * 0.35f;
            cv.translate(0, sinkY);
            cv.scale(1f + dk * 0.08f, sqY);
            p.setAlpha((int) (255 * (1 - dk)));
        }
        if (en.curF != null) {
            drawFrame(cv, en.curF, p.getAlpha());
            if (en.prevF != null && en.fadeT < 1f) {
                int prevAlpha = (int) ((1f - en.fadeT) * p.getAlpha());
                drawFrame(cv, en.prevF, prevAlpha);
            }
        } else {
            p.setColor(0xFFaa2233);
            rf.set(-30 * zoom, -ENEMY_H * 0.8f * zoom, 30 * zoom, 0);
            cv.drawOval(rf, p);
        }
        p.setAlpha(255);
        cv.restore();

        if (!en.dead) {
            float top = y - ENEMY_H * bs * zoom - 24;
            float bw = 90, bh = 8;
            rf.set(x - bw/2, top, x + bw/2, top + bh);
            paint.setColor(0xCC050508); cv.drawRoundRect(rf, 4, 4, paint);
            rf.right = rf.left + bw * (en.hp / (float)en.maxHp);
            paint.setColor(C_BLOOD); cv.drawRoundRect(rf, 4, 4, paint);

            if (en.heavy || en.beast) {
                float mtop = y - ENEMY_H * bs * zoom - 12;
                float mbw = 90, mbh = 5;
                rf.set(x - mbw/2, mtop, x + mbw/2, mtop + mbh);
                paint.setColor(0xCC050508); cv.drawRoundRect(rf, 3, 3, paint);
                rf.right = rf.left + mbw * (en.mana / (float)en.maxMana);
                paint.setColor(C_VIOLET); cv.drawRoundRect(rf, 3, 3, paint);
            }

            // C2: intent telegraph
            if (en.intent == 1) {
                paint.setColor(C_BRIGHT);
                paint.setAlpha(200 + (int) (55 * (float) Math.sin(en.animT * 8f)));
                float ix = x, iy = top - 14 * zoom;
                float s = 6 * zoom;
                cv.save();
                cv.translate(ix, iy);
                cv.rotate(45);
                cv.drawRect(-s, -s, s, s, paint);
                cv.restore();
                paint.setAlpha(255);
            } else if (en.intent == 2) {
                paint.setColor(C_BONE_DIM);
                paint.setAlpha(170);
                float ix = x, iy = top - 14 * zoom;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * zoom);
                cv.drawCircle(ix, iy, 5 * zoom, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
                paint.setAlpha(255);
            }
        }
    }

    private void drawBolts(Canvas cv) {
        for (Bolt b : boltPool) {
            if (!b.active) continue;
            float dx = b.tx - b.x0, dy = b.ty - b.y0;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1) d = 1;
            for (int t = 3; t >= 1; t--) {
                paint.setColor(C_BLOOD);
                paint.setAlpha(80 - t * 20);
                cv.drawCircle(sx(b.x - dx / d * t * 22), sy(b.y - dy / d * t * 22),
                        (12 - t * 2) * zoom, paint);
            }
            paint.setAlpha(220);
            paint.setColor(C_BLOOD);
            cv.drawCircle(sx(b.x), sy(b.y), 13 * zoom, paint);
            paint.setColor(0xFFffffff);
            cv.drawCircle(sx(b.x), sy(b.y), 6 * zoom, paint);
            paint.setAlpha(255);
        }
    }

    private void drawDmgs(Canvas cv) {
        paint.setTypeface(fBody);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        for (Dmg d : dmgPool) {
            if (!d.active) continue;
            float k = d.t / 0.8f;
            paint.setAlpha((int) (255 * (1 - k)));
            paint.setColor(d.col != 0 ? d.col : (d.val < 0 ? C_BRIGHT : C_BONE));
            // D2: scale-pop overshoot on spawn
            float scale;
            if (d.t < 0.09f) {
                float k2 = d.t / 0.09f;
                scale = 1.3f - 0.3f * k2;
            } else {
                scale = 1f;
            }
            float tx = sx(d.x), ty = sy(d.y) - k * 80;
            cv.save();
            cv.translate(tx, ty);
            cv.scale(scale, scale);
            paint.setTextSize(34);
            cv.drawText(d.txt, 0, 0, paint);
            cv.restore();
        }
        paint.setFakeBoldText(false);
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGame(Canvas cv) {
        cv.drawColor(GROUND_COL);

        if (storyMode && story != null) {
            sw().drawWorld(cv, camX - shakeX, camY - shakeY, zoom + zoomPunch, W, H, quality, loadT);
            drawFlatProps(cv);
        } else {
            drawGround(cv);
        }

        if (storyMode && story != null && story.hasObjective) {
            hexToWorld(story.objectiveQ, story.objectiveR, FW_A);
            float ox = sx(FW_A[0]), oy = sy(FW_A[1]);
            if (ox < 50 || ox > W - 50 || oy < 50 || oy > H - 50) {
                float cx = W / 2f, cy = H / 2f;
                float ang = (float) Math.atan2(oy - cy, ox - cx);
                float ax = cx + (float) Math.cos(ang) * (W / 3f);
                float ay = cy + (float) Math.sin(ang) * (H / 3f);
                cv.save();
                cv.translate(ax, ay);
                cv.rotate(ang * 180f / (float) Math.PI);
                paint.setColor(C_CYAN);
                paint.setStyle(Paint.Style.FILL);
                hexPath.reset();
                hexPath.moveTo(14 * zoom, 0);
                hexPath.lineTo(-10 * zoom, -10 * zoom);
                hexPath.lineTo(-10 * zoom, 10 * zoom);
                hexPath.close();
                cv.drawPath(hexPath, paint);
                cv.restore();
            } else {
                float pulse = 0.8f + 0.2f * (float) Math.sin(loadT * 4f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(C_CYAN);
                paint.setAlpha((int) (150 * pulse));
                cv.drawCircle(ox, oy, 15 * zoom * pulse, paint);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * zoom);
                cv.drawCircle(ox, oy, 20 * zoom, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
                paint.setAlpha(80);
                cv.drawRect(ox - 10 * zoom, oy - 200 * zoom, ox + 10 * zoom, oy, paint);
                paint.setAlpha(255);
            }
        }

        if (hexesShown) drawMoveFan(cv);
        if (attackRangeShown > 0) drawAttackRange(cv);
        if (targetEnemy != null && !targetEnemy.dead) {
            worldToHex(targetEnemy.x, targetEnemy.y, IH_A);
            hexToWorld(IH_A[0], IH_A[1], FW_A);
            drawHexRing(cv, sx(FW_A[0]), sy(FW_A[1]), 0xFFefe6dd, 0x22efe6dd);
        }
        drawRune(cv);
        drawPuffs(cv);

        drawDecals(cv);
        drawSorted(cv);
        if (storyMode && actors != null)
            actors.draw(cv, camX - shakeX, camY - shakeY, zoom + zoomPunch, W, H, loadT);
        drawRoomProps(cv);
        drawBites(cv);
        if (storyMode && map != null)
            map.drawFront(cv, camX - shakeX, camY - shakeY, zoom + zoomPunch, W, H);
        if (storyMode && !storyTest) drawFog(cv);
        drawParticles(cv);

        drawBlasts(cv);
        drawBolts(cv);
        drawSlashes(cv);
        drawDmgs(cv);

        if (gameOverlay != null) {
            rf.set(0, 0, W, H);
            // E2: low-HP heartbeat pulse on the vignette
            if (player != null && player.hp > 0 && player.hp <= 30) {
                hbPulse += 1f/60f * 4f;
                float beat = (float) Math.abs(Math.sin(hbPulse));
                paint.setColor(C_BRIGHT);
                paint.setAlpha((int) (20 * beat));
                cv.drawRect(0, 0, W, H, paint);
                paint.setAlpha(255);
            }
            cv.drawBitmap(gameOverlay, null, rf, paint);
        }

        if (quality > 0) {
            paint.setColor(C_EMBER);
            for (Ember em : embers) {
                paint.setAlpha((int) (40 + em.s));
                cv.drawCircle(em.x, em.y, 1.5f + em.s / 40f, paint);
            }
            paint.setAlpha(255);
        }

        drawUI(cv);
        if (storyMode && story != null) {
            story.drawDialog(cv, W, H, paint, fBody);
            story.drawTitle(cv, W, H, paint, fLogo);
            story.drawHud(cv, W, H, paint, fBody, fLogo);
        }
        if (storyMode && storyTest) drawPosCard(cv);
        if (fadeState != 0) {
            paint.setColor(0xFF000000);
            paint.setAlpha((int) (fadeT * 255f));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }

        if (hurtT > 0) {
            paint.setColor(C_BRIGHT);
            paint.setAlpha((int) (hurtT / 0.3f * 100));
            cv.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }
        if (phaseT < 1.2f) {
            int a = phaseT < 0.9f ? 220 : (int) ((1.2f - phaseT) / 0.3f * 220);
            paint.setAlpha(a);
            // D3: overshoot scale-in on the banner
            float bsc = phaseT < 0.12f
                    ? (1.15f - 0.15f * (phaseT / 0.12f))
                    : 1f;
            paint.setTextSize(64);
            paint.setTypeface(fLogo);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(phase == PH_PLAYER ? C_MAGENTA : C_BRIGHT);
            String btxt = phase == PH_PLAYER ? "YOUR TURN" : "ENEMY TURN";
            cv.save();
            cv.translate(W / 2f, H * 0.3f);
            cv.scale(bsc, bsc);
            cv.drawText(btxt, 0, 0, paint);
            float tw = paint.measureText(btxt) * 0.5f;
            float sweep = Math.min(1f, phaseT / 0.9f);
            cv.save();
            cv.clipRect(-tw - 10, -30, -tw - 10 + (tw * 2 + 80) * sweep, 30);
            ekgPath.reset();
            ekgPath.moveTo(tw + 18, -20);
            ekgPath.lineTo(tw + 34, -20);
            ekgPath.lineTo(tw + 38, -26);
            ekgPath.lineTo(tw + 44, -7);
            ekgPath.lineTo(tw + 48, -27);
            ekgPath.lineTo(tw + 76, -20);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            cv.drawPath(ekgPath, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0);
            cv.restore();
            cv.restore();
            paint.setTypeface(fBody);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        if (deadT > 0) {
            paint.setColor(C_BRIGHT);
            paint.setTextSize(90);
            paint.setTextAlign(Paint.Align.CENTER);
            cv.drawText("YOU DIED", W / 2f, H / 2f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (storyMode) sw().drawOver(cv, camX - shakeX, camY - shakeY, zoom + zoomPunch, W, H, quality, loadT);
    }

    private void drawFog(Canvas cv) {
        float z = zoom + zoomPunch;
        float halfW = W / (2f * z), halfH = H / (2f * z);
        float rowY = HEX * 1.5f * SQUASH;
        float colX = HEX * 1.7320508f;
        int r0 = (int) Math.floor((camY - halfH) / rowY) - 1;
        int r1 = (int) Math.floor((camY + halfH) / rowY) + 1;
        int q0 = (int) Math.floor((camX - halfW) / colX - r1 / 2f) - 1;
        int q1 = (int) Math.floor((camX + halfW) / colX - r0 / 2f) + 1;
        float s = HEX * z * 1.07f;
        paint.setColor(0xFF070409);
        for (int r = r0; r <= r1; r++) {
            for (int q = q0; q <= q1; q++) {
                if (sw().explored(q, r)) continue;
                SceneMap.hexToWorld(q, r, FW_A);
                float cx = sx(FW_A[0]), cy = sy(FW_A[1]);
                hexPath.reset();
                for (int k = 0; k < 6; k++) {
                    float x = cx + FOG_UH[k * 2] * s;
                    float y = cy + FOG_UH[k * 2 + 1] * s;
                    if (k == 0) hexPath.moveTo(x, y); else hexPath.lineTo(x, y);
                }
                hexPath.close();
                cv.drawPath(hexPath, paint);
            }
        }
    }

    private int putInt(char[] buf, int off, int v) {
        if (v < 0) { buf[off++] = '-'; v = -v; }
        int st = off;
        do { buf[off++] = (char) ('0' + v % 10); v /= 10; } while (v > 0);
        for (int i = st, j = off - 1; i < j; i++, j--) {
            char t = buf[i]; buf[i] = buf[j]; buf[j] = t;
        }
        return off;
    }

    private void drawRoomProps(Canvas cv) {
        StoryWorld sw = sw();
        if (sw.map == null || !sw.map.roomOn()) return;
        for (int i = 0; i < sw.placedProps.size(); i++) {
            StoryWorld.Prop pr = sw.placedProps.get(i);
            if (pr.sheet != 4) continue;
            if (arrRoomProps == null || pr.idx < 0 || pr.idx >= arrRoomProps.length) continue;
            Frame f = arrRoomProps[pr.idx];
            if (f == null || f.bmp == null || f.bmp.isRecycled()) continue;
            float px = sx(pr.x), py = sy(pr.y);
            if (px < -300 || px > W + 300 || py < -300 || py > H + 300) continue;
            float h = TH * 1.9f * pr.scale;
            float s = h / f.ref;
            rf.set(px - f.bmp.getWidth() * s / 2f, py - f.bmp.getHeight() * s,
                   px + f.bmp.getWidth() * s / 2f, py);
            cv.drawBitmap(f.bmp, null, rf, paint);
        }
    }

    private void drawPosCard(Canvas cv) {
        worldToHex(player.x, player.y, IH_A);
        int n = putInt(posBuf, 0, IH_A[0]);
        posBuf[n++] = ' ';
        n = putInt(posBuf, n, IH_A[1]);
        paint.setTypeface(fBody);
        paint.setTextSize(24);
        float tw = paint.measureText(posBuf, 0, n);
        paint.setColor(0xCC0e0709);
        rf.set(16, 16, 16 + tw + 24, 56);
        cv.drawRoundRect(rf, 10, 10, paint);
        paint.setColor(0xAAefe6dd);
        cv.drawText(posBuf, 0, n, 28, 44, paint);
    }

    private void layoutDock() {
        float panelW = Math.min(W - 24, 620);
        float panelH = 92;
        float off = (1 - dockSlide) * (panelH + 60);
        float y0 = H - panelH - 12 + off;
        dockPanel.set(W / 2f - panelW / 2, y0, W / 2f + panelW / 2, y0 + panelH);
        float pad = 14, gap = 10, bw = 108;
        float x = dockPanel.left + pad;
        dockEnd.set(x, y0 + pad, x + bw, y0 + panelH - pad);
        x = dockEnd.right + gap + 76 + gap;
        for (int i = 0; i < 3; i++) {
            dockAtk[i].set(x, y0 + pad, x + bw, y0 + panelH - pad);
            x += bw + gap;
        }
    }

    private void drawUI(Canvas cv) {
        if (dockSlide < 0.02f) return;
        layoutDock();
        int baseA = (int) (dockSlide * 255);
        paint.setAlpha(baseA);
        paint.setTextAlign(Paint.Align.CENTER);

        cutRect(dockPanel, 14);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD40e0709);
        cv.drawPath(btnPath, paint);
        cv.save();
        cv.clipPath(btnPath);
        paint.setColor(0x14efe6dd);
        cv.drawRect(dockPanel.left, dockPanel.top, dockPanel.right, dockPanel.top + 2, paint);
        paint.setColor(0x0Affffff);
        cv.drawRect(dockPanel.left, dockPanel.top + 2, dockPanel.right, dockPanel.top + 3, paint);
        cv.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0x35ff2747);
        cv.drawPath(btnPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0);

        drawDockButton(cv, dockEnd, "END", C_BONE_DIM, true, menuPress == 5, false);
        String[] lbl = { "REND", "BOLT", "NOVA" };
        int[] acc = { C_BRIGHT, C_EMBER, C_VIOLET };
        for (int i = 0; i < 3; i++) {
            boolean on = attackRangeShown == i + 1;
            boolean en = canAct() && mana >= player.hero.attacks[i].mana;
            drawDockButton(cv, dockAtk[i], lbl[i], acc[i], en || on, menuPress == 6 + i, on);
        }

        for (int i = 0; i < 2; i++) {
            float px = dockEnd.right + 30 + i * 36, py = dockPanel.centerY();
            if (coinBmp != null) {
                paint.setAlpha((int) (dockSlide * (i < player.actionsLeft ? 255 : 70)));
                rf.set(px - 14, py - 14, px + 14, py + 14);
                cv.drawBitmap(coinBmp, null, rf, paint);
            } else {
                paint.setAlpha(baseA);
                paint.setColor(i < player.actionsLeft ? C_MAGENTA : 0xFF222222);
                cv.save();
                cv.translate(px, py);
                cv.rotate(45);
                cv.drawRect(-12, 12, 12, 12, paint);
                cv.restore();
            }
        }
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDockButton(Canvas cv, RectF r, String label, int accent,
                                boolean enabled, boolean pressed, boolean armed) {
        cutRect(r, 10);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0x50301f4a : 0x2A1c1230);
        cv.drawPath(btnPath, paint);
        if (pressed) {
            cv.save();
            cv.clipPath(btnPath);
            paint.setColor(0x22ffffff);
            cv.drawRect(r.centerX() - 16, r.top, r.centerX() + 16, r.bottom, paint);
            cv.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(armed ? 3.5f : 2f);
        paint.setColor(enabled ? accent : 0xFF222222);
        cv.drawPath(btnPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? C_BONE : 0xFF555555);
        paint.setTypeface(fBody);
        paint.setTextSize(24);
        paint.setFakeBoldText(true);
        cv.drawText(label, r.centerX(), r.centerY() + 8, paint);
        paint.setFakeBoldText(false);
        paint.setStrokeWidth(0);
    }

    private boolean uiZone(float x, float y) {
        if (dockSlide > 0.9f) {
            layoutDock();
            return dockPanel.contains(x, y);
        }
        return false;
    }

    private static float pointerDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state == STATE_MENU) return onMenuTouch(e);
        if (state == STATE_SELECT) return onSelectTouch(e);
        if (state == STATE_CHAPTER) return onChapterTouch(e);
        if (deadT > 0) return true;
        // C5: input blocked during enemy phase, but panning allowed via the path below
        boolean enemyPhase = (phase != PH_PLAYER);
        if (storyMode && story != null && story.dialogUp) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) story.tap();
            return true;
        }
        // C3: the auto-scene owns every touch until it hands control back.
        if (storyMode && !storyFight && sw().cutsceneHold()) return true;
        int act = e.getActionMasked();

        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            moved = false; panning = false; pinching = false;
            flingX = 0; flingY = 0; velX = 0; velY = 0;
            lastMoveT = e.getEventTime();
            menuPress = 0;
            if (state == STATE_GAME && phase == PH_PLAYER && dockSlide > 0.9f) {
                layoutDock();
                if (dockEnd.contains(downX, downY)) menuPress = 5;
                else for (int i = 0; i < 3; i++)
                    if (dockAtk[i].contains(downX, downY)) menuPress = 6 + i;
            }
            return true;
        }
        if (act == MotionEvent.ACTION_POINTER_DOWN) {
            if (e.getPointerCount() == 2) {
                pinching = true;
                moved = true;
                pinchDist0 = pointerDist(e);
                pinchZoom0 = zoom;
            }
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (pinching && e.getPointerCount() >= 2) {
                scriptZoom = false;
                if (pinchDist0 > 1) {
                    float newZoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN,
                            pinchZoom0 * pointerDist(e) / pinchDist0));
                    // C4: anchor to pinch centroid
                    float cx = (e.getX(0) + e.getX(1)) * 0.5f;
                    float cy = (e.getY(0) + e.getY(1)) * 0.5f;
                    float wx = camX + (cx - W * 0.5f) / zoom;
                    float wy = camY + (cy - H * 0.5f) / zoom;
                    zoom = newZoom;
                    camX = wx - (cx - W * 0.5f) / zoom;
                    camY = wy - (cy - H * 0.5f) / zoom;
                }
                return true;
            }
            if (downX < -9000) return true;
            float x = e.getX(), y = e.getY();
            if (!moved && Math.sqrt((x - downX) * (x - downX) + (y - downY) * (y - downY)) > 26) {
                moved = true;
                panning = !uiZone(downX, downY);
                lastPX = downX; lastPY = downY;
                lastMoveT = e.getEventTime();
            }
            if (moved && panning) {
                long now = e.getEventTime();
                float dtm = (now - lastMoveT) / 1000f;
                if (dtm > 0.001f) {
                    velX = velX * 0.7f + (-(x - lastPX) / zoom / dtm) * 0.3f;
                    velY = velY * 0.7f + (-(y - lastPY) / zoom / dtm) * 0.3f;
                    lastMoveT = now;
                }
                camX -= (x - lastPX) / zoom;
                camY -= (y - lastPY) / zoom;
                exploring = true;
                exploreT = 0;
                lastPX = x; lastPY = y;
            }
            return true;
        }
        if (act == MotionEvent.ACTION_POINTER_UP) {
            pinching = false;
            return true;
        }
        if (act != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        downX = -9999;
        if (pinching) { pinching = false; return true; }
        if (moved) {
            if (panning) { flingX = velX; flingY = velY; }
            panning = false;
            return true;
        }
        panning = false;
        // cutscene lock: no gameplay taps while the director is driving
        if (storyMode && !storyFight && (swActive || camMode != 0)) return true;

        if (enemyPhase) return true; // C5: block gameplay taps during enemy phase

        menuPress = 0;
        if (phase == PH_PLAYER && dockSlide > 0.9f) {
            layoutDock();
            if (dockEnd.contains(x, y)) {
                sound.play("ui");
                endPlayerTurn();
                return true;
            }
            for (int i = 0; i < 3; i++) {
                if (dockAtk[i].contains(x, y)) {
                    if (canAct() && (mana >= player.hero.attacks[i].mana
                            || attackRangeShown == i + 1)) {
                        attackRangeShown = (attackRangeShown == i + 1) ? 0 : i + 1;
                        hexesShown = false; targetEnemy = null;
                        atkDirty = true;
                        sound.play("ui");
                    }
                    return true;
                }
            }
            if (dockPanel.contains(x, y)) return true;
        }

        float wx = camX + (x - W / 2f) / zoom;
        float wy = camY + (y - H / 2f) / zoom;
        worldToHex(wx, wy, TW_A);
        worldToHex(player.x, player.y, TW_B);
        int dTap = hexDist(TW_B[0], TW_B[1], TW_A[0], TW_A[1]);

        Player tappedHero = null;
        for (Player pp : party) {
            worldToHex(pp.x, pp.y, TW_C);
            if (TW_C[0] == TW_A[0] && TW_C[1] == TW_A[1]) { tappedHero = pp; break; }
        }
        if (tappedHero != null) {
            if (player != tappedHero) {
                player = tappedHero;
                voice = player.hero.voice;
                sound.play(voice + "_select");
            }
            hexesShown = !hexesShown;
            attackRangeShown = 0;
            fanDirty = true;
            return true;
        }

        if (dTap == 0) {
            sound.play(voice + "_select");
            hexesShown = !hexesShown;
            attackRangeShown = 0;
            fanDirty = true;
            return true;
        }

        if (attackRangeShown == 3 && canAct()
                && dTap <= player.hero.attacks[2].range) {
            player.actionsLeft--;
            player.facing = wx >= player.x ? 1 : -1;
            mana -= player.hero.attacks[2].mana;
            player.hero.startAttack(2, null);
            sound.play(player.hero.atkSfx[2]);
            sound.play(voice + "_attack");
            attackRangeShown = 0;
            hexesShown = false;
            targetEnemy = null;
            return true;
        }

        Enemy tapped = null;
        float bestY = -1f;
        for (Enemy en : enemies) {
            if (en.dead) continue;
            float ex = sx(en.x), ey = sy(en.y) + FOOT_DROP * zoom;
            float hw = ENEMY_H * 0.4f * zoom;
            if (x >= ex - hw && x <= ex + hw
                    && y >= ey - ENEMY_H * zoom - 20 && y <= ey + 10) {
                if (en.y > bestY) { bestY = en.y; tapped = en; }
            }
        }
        if (tapped == null) {
            for (Enemy en : enemies) {
                if (en.dead) continue;
                worldToHex(en.x, en.y, TW_C);
                if (TW_C[0] == TW_A[0] && TW_C[1] == TW_A[1]) { tapped = en; break; }
            }
        }

        if (tapped != null) {
            int range = attackRangeShown > 0 ? player.hero.attacks[attackRangeShown - 1].range : 0;
            if (canAct() && range > 0 && dTap <= range) {
                if (targetEnemy != tapped) {
                    targetEnemy = tapped;
                    sound.play("ui");
                } else {
                    int ai = attackRangeShown - 1;
                    player.actionsLeft--;
                    player.facing = tapped.x >= player.x ? 1 : -1;
                    mana -= player.hero.attacks[ai].mana;
                    player.hero.startAttack(ai, tapped);
                    sound.play(player.hero.atkSfx[ai]);
                    sound.play(voice + "_attack");
                    targetEnemy = null;
                    attackRangeShown = 0;
                    hexesShown = false;
                }
            }
            return true;
        }

        // C1: consume buffered move if the hero just arrived
        if (player.qT > 0 && player.qQ != Integer.MIN_VALUE && !player.isMoving() && !player.isAttacking()) {
            int qq = player.qQ, qr = player.qR;
            player.clearQueue();
            if (tryMoveTo(qq, qr)) return true;
        }

        if (player.isMoving() && hexesShown && dTap >= 1) {
            // queue the move while hero is still walking
            player.queueHex(TW_A[0], TW_A[1]);
            hexesShown = false;
            return true;
        }
        if (hexesShown && tryMoveTo(TW_A[0], TW_A[1])) return true;
        return true;
    }

    // =====================================================================
    // CONTINUOUS-MAP BRIDGE — invoked by StoryWorld
    // =====================================================================
    public boolean isDialogBlocking() { return story != null && story.dialogUp; }

    // =====================================================================
    // CINEMATIC DIRECTOR — script-driven camera, walk, fx
    // =====================================================================
    public void showTitle(String t) {
        if (story != null) { story.title = t; story.titleT = 0f; }
    }
    public boolean isScriptWalking() { return swActive; }
    public void scriptFace(int dir) { player.facing = dir; }
    public void startFade(int ms) {
        fadeDur = ms > 0 ? ms : 1000;
        fadeState = 1;
        fadeT = 0f;
    }
    public void startFadeIn(int ms) {
        fadeDur = ms > 0 ? ms : 1000;
        fadeState = 3;
        fadeT = 1f;
    }
    public boolean isFadeBlack() { return fadeState == 2; }
    public void scriptWalk(int q, int r, float dur) {
        SceneMap.hexToWorld(q, r, FW_A);
        swFromX = player.x; swFromY = player.y;
        swToX = FW_A[0]; swToY = FW_A[1];
        swT = 0f; swDur = Math.max(0.1f, dur);
        swActive = true; swGlide = false; swLift = 0f;
        player.facing = swToX >= swFromX ? 1 : -1;
        camMode = 0; camSnap = false;
    }
    public void scriptGlide(int q, int r, float dur) {
        SceneMap.hexToWorld(q, r, FW_A);
        swFromX = player.x; swFromY = player.y;
        swToX = FW_A[0]; swToY = FW_A[1];
        swT = 0f; swDur = Math.max(0.1f, dur);
        swActive = true; swGlide = true; swLift = 0f;
        player.facing = swToX >= swFromX ? 1 : -1;
        camMode = 0; camSnap = false;
        try { sound.play("glide"); } catch (Exception ignored) {}
    }
    public void scriptCamPan(float wx, float wy, int ms) {
        camMode = 2;
        panFromX = camX; panFromY = camY;
        panToX = wx; panToY = wy;
        panT = 0f; panDur = Math.max(0.05f, ms / 1000f);
    }
    public void scriptCamPush(int ms) { pushT = 0f; pushDur = Math.max(0.05f, ms / 1000f); }
    public void scriptZoom(float target, int ms) {
        zoomTarget = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, target));
        scriptZoom = true;
    }
    public void scriptCamFollow(String name) { camMode = 1; camFollowName = name; }
    public void scriptCamRelease() {
        camMode = 0; camFollowName = null;
        pushT = -1f; zoomPunch = 0f; camSnap = false;
    }
    public void fxPoint(String kind, float wx, float wy) {
        if ("debris_arc".equals(kind)) {
            for (int i = 0; i < 16; i++) {
                for (Particle p : particlePool) {
                    if (!p.active) {
                        p.x = wx + (float) (Math.random() * 160 - 80);
                        p.y = wy - (float) (Math.random() * 60);
                        p.vx = (float) (Math.random() * 260 - 130);
                        p.vy = -80 - (float) (Math.random() * 220);
                        p.grav = 500;
                        p.life = 0.9f + (float) Math.random() * 0.5f;
                        p.t = 0;
                        p.col = (i % 3 == 0) ? C_EMBER : ((i % 3 == 1) ? C_BLOOD : C_BONE_DIM);
                        p.active = true;
                        break;
                    }
                }
            }
        } else {
            spawnBlast(wx, wy);
        }
    }
    public void fxActor(String name, String kind) {
        fxActorName = name; fxActorKind = kind; fxActorT = 2.5f;
    }
    private float puffGlideT = 0f;
    private void updateScriptWalk(float dt) {
        if (!swActive) return;
        swT += dt;
        float t = Math.min(1f, swT / swDur);
        float e = t * t * (3f - 2f * t);
        player.x = swFromX + (swToX - swFromX) * e;
        player.y = swFromY + (swToY - swFromY) * e;
        player.targetX = player.x; player.targetY = player.y;
        if (swGlide) {
            swLift = (float) Math.sin(t * 3.14159f) * 150f;
            puffGlideT += dt;
            if (puffGlideT > 0.09f) {
                puffGlideT = 0f;
                spawnPuff(player.x + (float) (Math.random() * 30 - 15),
                          player.y + (float) (Math.random() * 10 - 5));
            }
        }
        if (t >= 1f) {
            if (swGlide) {
                swGlide = false; swLift = 0f;
                shakeT = Math.max(shakeT, 0.12f);
                try { sound.play("land"); } catch (Exception ignored) {}
                for (int i = 0; i < 6; i++)
                    spawnPuff(player.x + (float) (Math.random() * 60 - 30),
                              player.y + (float) (Math.random() * 16 - 8));
            }
            swActive = false;
        }
    }
    private void updateDirector(float dt) {
        if (camMode == 2) {
            if (panT < panDur) {
                panT += dt;
                float t = Math.min(1f, panT / panDur);
                float e = t * t * (3f - 2f * t);
                camX = panFromX + (panToX - panFromX) * e;
                camY = panFromY + (panToY - panFromY) * e;
            }
        } else if (camMode == 1 && actors != null && camFollowName != null
                && !StoryWorld.PLAYER_KEY.equals(camFollowName)) {
            StoryActor a = actors.get(camFollowName);
            if (a != null && H > 0) {
                float k = 1 - (float) Math.exp(-dt * 8);
                camX += (a.x - camX) * k;
                camY += ((a.y - (H * 0.28f) / zoom) - camY) * k;
            }
        }
        if (pushT >= 0 && pushT < 1e7f) {
            pushT += dt;
            float t = Math.min(1f, pushT / pushDur);
            zoomPunch = 0.35f * (t * t * (3f - 2f * t));
            if (t >= 1f) pushT = 1e8f; // hold the push until CAM_RELEASE
        }
        if (fxActorT > 0) updateFxActor(dt);
    }
    private void updateFxActor(float dt) {
        fxActorT -= dt;
        float ax, ay;
        if (StoryWorld.PLAYER_KEY.equals(fxActorName)) { ax = player.x; ay = player.y; }
        else if (actors != null) {
            StoryActor a = actors.get(fxActorName);
            if (a == null) { fxActorT = 0f; return; }
            ax = a.x; ay = a.y;
        } else return;
        boolean crack = fxActorKind != null && fxActorKind.indexOf("crackle") >= 0;
        for (int i = 0; i < 2; i++) {
            for (Particle p : particlePool) {
                if (!p.active) {
                    p.x = ax + (float) (Math.random() * 40 - 20);
                    p.y = ay - 90 - (float) (Math.random() * 30);
                    if (crack) {
                        p.vx = (float) (Math.random() * 30 - 15);
                        p.vy = -40 - (float) (Math.random() * 60);
                        p.grav = -20;
                        p.life = 0.35f + (float) Math.random() * 0.25f;
                        p.col = (i == 0) ? C_VIOLET : C_MAGENTA;
                    } else {
                        p.vx = (float) (Math.random() * 16 - 8);
                        p.vy = 30 + (float) (Math.random() * 50);
                        p.grav = 300;
                        p.life = 0.5f + (float) Math.random() * 0.3f;
                        p.col = C_BLOOD;
                    }
                    p.t = 0;
                    p.active = true;
                    break;
                }
            }
        }
    }
    public void showDialog(String speaker, String text) {
        if (story == null) return;
        story.speaker = speaker; story.text = text;
        story.dialogUp = true; story.tw = 0; story.mode = Story.MODE_DIALOG;
    }
    public void onProgressFlag(String flag) { if (story != null) story.onProgressFlag(flag); }
    public void scriptCamLook(int q, int r, int ms) {
        float[] hw = new float[2];
        SceneMap.hexToWorld(q, r, hw);
        camLookX = hw[0]; camLookY = hw[1];
        camLookT = ms / 1000f;
    }
    public void fxShake(int ms) { shakeT = Math.max(shakeT, ms / 1000f); }
    public void fxFlash(int ms) { hurtT = Math.max(hurtT, ms / 1000f); }
    public void fxDecal(String kind) { spawnDecal(player.x + player.facing * 40, player.y); }
    public int enemiesAlive() {
        int n = 0; for (Enemy e : enemies) if (!e.dead) n++; return n;
    }
    public void spawnReinforcement(String type, int q, int r) {
        float[] hw = new float[2];
        SceneMap.hexToWorld(q, r, hw);
        Enemy e = new Enemy();
        e.x = hw[0]; e.y = hw[1];
        e.facing = -1;   // F4: story enemies face the player (west)
        if ("infantry".equals(type)) {
            e.heavy = true;
            e.hp = 60; e.maxHp = 60;
            e.mana = 100; e.maxMana = 100;
            e.weapon = 2;
            e.speed = 130f;
        } else if ("beast".equals(type)) {
            e.beast = true;
            e.hp = 120; e.maxHp = 120;
            e.speed = 900f;   // F5: glide at lunge pace
        }
        enemies.add(e);
    }
    public void refreshDock() { fanDirty = true; }
    public void noteWave() { if (story != null) story.flashNote("More are coming..."); }
    public void flashNote(String txt) { if (story != null) story.flashNote(txt); }
    public void onActComplete() {
        if (story != null) story.showActCard("END OF ACT ONE");
    }

    // E3: tiered haptics — 0 light, 1 medium, 2 heavy
    private void hapticTiered(int tier) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.os.Vibrator v = (android.os.Vibrator)
                        getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                long[] pat; int[] amp;
                if (tier >= 2) { pat = new long[]{0, 55}; amp = new int[]{0, 255}; }
                else if (tier == 1) { pat = new long[]{0, 30}; amp = new int[]{0, 160}; }
                else              { pat = new long[]{0, 18}; amp = new int[]{0, 90}; }
                v.vibrate(android.os.VibrationEffect.createWaveform(pat, amp, -1));
            } else {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
        } catch (Exception ignored) {}
    }
}
