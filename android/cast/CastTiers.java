package io.github.lswlc33.zeehowebnext.cast;

import org.json.JSONObject;

/**
 * 档位表与自动降级。
 *
 * <p>来源：{@code docs/CAST.md} §8、反编译 {@code p.java}（{@code d()} 枚举顺序 = 降级顺序、
 * {@code e()} 编码参数、{@code d(w,h,fps)} level 查表、{@code b()/f()} 降级条件），
 * 行为基准是本仓 {@code sdk/vehicle-cast.js} 的 {@code planCapture()/pickAvcLevel()/shouldDowngrade()}。
 *
 * <p>为什么档位要“从高到低只降不升”：车机是拉帧方，解码能力（S905L3AB 一类盒子）是瓶颈；
 * 一旦某档位被车机拒绝（媒体通道断开），只有更低负载的档位才可能成功。升档没有意义，
 * 因为失败原因永远是“车机吃不下”。
 */
public final class CastTiers {

    private CastTiers() {
    }

    /* ================= 档位（p.d 枚举顺序） ================= */

    public static final String PERFORMANCE = "performance";
    public static final String BITRATE_SAFE = "bitrate-safe";
    public static final String COMPATIBILITY = "compatibility";
    public static final String BALANCED = "balanced";
    public static final String RESOLUTION_SAFE = "resolution-safe";
    public static final String DECODER_SAFE = "decoder-safe";

    /** 降级顺序，index 越大越保守。 */
    public static final String[] TIERS = new String[]{
            PERFORMANCE, BITRATE_SAFE, COMPATIBILITY, BALANCED, RESOLUTION_SAFE, DECODER_SAFE
    };

    /** 起始档位（p.e 的默认，也是 start(options.tier) 的缺省）。 */
    public static final String DEFAULT_TIER = PERFORMANCE;

    /** 降级次数上限：{@code p.f7884a = 6 - 1 = 5}（CAST.md §8.1，r1.f7986y0 的守卫是 {@code i9 < 5}）。 */
    public static final int MAX_RETRIES = 5;

    /**
     * 「档位学习」门槛：跑满 15 s 且发过 ≥150 帧（p.b，CAST.md §8.1）。
     *
     * <p>注意它**不是**降级条件——降级看的是“15 s 内就断了”（见
     * {@link #shouldDowngrade}）。两个门槛用同一个 15 s 常量，是因为源头是同一个
     * {@code DEFAULT_REMOTE_TIMEOUT_MILLIS}，别把它们合并成一个判断。
     */
    public static final long LEARN_THRESHOLD_MS = 15000L;
    public static final int LEARN_THRESHOLD_FRAMES = 150;

    /** 尺寸对齐粒度：H.264 宏块 16（p.a：{@code max(16, v & -16)}）。 */
    public static final int ALIGN = 16;
    /** 编码器 level 缺省 id：3.1（512）。 */
    public static final int LEVEL_31_ID = 512;
    /** 编码器 level 兜底 id：3.0（256）。 */
    public static final int LEVEL_30_ID = 256;
    /** resolution-safe / decoder-safe 的宏块总数上限（p.e 的 while 循环条件 1620）。 */
    public static final int MAX_MACROBLOCKS = 1620;
    /** 缩分辨率的目标框（p.e：800x480）。 */
    public static final int SAFE_MAX_WIDTH = 800;
    public static final int SAFE_MAX_HEIGHT = 480;

    /** balanced 固定码率 1.5 MiB。 */
    public static final int BITRATE_BALANCED = 1572864;
    /** decoder-safe 固定码率 1 MiB。 */
    public static final int BITRATE_DECODER_SAFE = 1048576;
    /** performance 在非 S905L3AB 机型上的固定码率 2.5 MiB（p.e 的 2621440）。 */
    public static final int BITRATE_PERFORMANCE_FIXED = 2621440;

    /** 码率可选区间（p.h：256000..8000000，超区间一律回落到 2 MiB）。 */
    public static final int BITRATE_MIN = 256000;
    public static final int BITRATE_MAX = 8000000;

    /** 车机芯片标记：只有它命中时 performance 档才“按请求码率”（p.e 的 "s905l3ab" 分支）。 */
    public static final String CHIPSET_S905L3AB = "s905l3ab";
    public static final String CHIPSET_GENERIC = "generic";

    /**
     * 一次会话实际采用的编码参数。
     *
     * <p>{@code codec} 与 {@code levelId} 是两件事：{@code codec} 进命令 17 的 9 字节，
     * {@code levelId} 只在 compatibility 档写进 MediaFormat 的 "level"。
     */
    public static final class Plan {
        public final String tier;
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrate;
        /** 编码器号，H.264 = 2（命令 17 首字段）。 */
        public final int codec;
        /** MediaCodec 的 "level"（AVCLevel 编码值：512=3.1、256=3.0…）。 */
        public final int levelId;
        /** level 的可读名，仅诊断用。 */
        public final String levelName;
        /**
         * compatibility 档标记（p.e 的 z8）。
         *
         * <p>注意：它**不**决定 MediaFormat 是否带 profile/level——按 CAST.md §8，
         * 编码器第一次 configure 一定带 Baseline+level，被拒才回退 codec-default。
         * 这个字段只用于诊断/降级提示。
         */
        public final boolean baselineProfile;
        /** 车机是否支持 extended 数据帧（来自采集配置第 29 字节）。 */
        public final boolean extended;

        Plan(String tier, int width, int height, int fps, int bitrate, int codec,
             int levelId, String levelName, boolean baselineProfile, boolean extended) {
            this.tier = tier;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            this.codec = codec;
            this.levelId = levelId;
            this.levelName = levelName;
            this.baselineProfile = baselineProfile;
            this.extended = extended;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            put(json, "compatibilityTier", tier);
            put(json, "width", width);
            put(json, "height", height);
            put(json, "fps", fps);
            put(json, "bitrate", bitrate);
            put(json, "codec", codec);
            put(json, "avcLevel", levelName);
            put(json, "avcLevelId", levelId);
            // 实际 MediaFormat 一定先试 baseline；这里给页面的只是“这一档的意图”
            put(json, "avcProfile", baselineProfile ? "baseline" : "codec-default");
            put(json, "extended", extended);
            return json;
        }

        private static void put(JSONObject json, String key, Object value) {
            try {
                json.put(key, value);
            } catch (Exception ignored) {
                // 常量 key，不会抛
            }
        }
    }

    /**
     * start(options) 里的“上限覆盖”：只允许把负载**往下压**。
     *
     * <p>为什么是上限而不是直接指定：车机在命令 16 里会给出它希望的宽高/fps/码率，
     * 页面的画质设置只能在车机给的基础上再收紧，不能反过来要求车机吃更大的流。
     */
    public static final class Caps {
        public static final int DEFAULT_MAX_WIDTH = 4096;
        public static final int DEFAULT_MAX_HEIGHT = 4096;
        public static final int DEFAULT_MAX_FPS = 60;
        public static final int DEFAULT_MIN_BITRATE = BITRATE_MIN;
        public static final int DEFAULT_MAX_BITRATE = BITRATE_MAX;

        public int maxWidth = DEFAULT_MAX_WIDTH;
        public int maxHeight = DEFAULT_MAX_HEIGHT;
        public int maxFps = DEFAULT_MAX_FPS;
        public int minBitrate = DEFAULT_MIN_BITRATE;
        public int maxBitrate = DEFAULT_MAX_BITRATE;

        /** 用 options 里的 width/height/fps/bitrate 构造（都按“上限”理解，缺省即不限制）。 */
        public static Caps fromJson(JSONObject options) {
            Caps caps = new Caps();
            if (options == null) {
                return caps;
            }
            int width = options.optInt("width", 0);
            if (width >= 16) {
                caps.maxWidth = Math.min(DEFAULT_MAX_WIDTH, width);
            }
            int height = options.optInt("height", 0);
            if (height >= 16) {
                caps.maxHeight = Math.min(DEFAULT_MAX_HEIGHT, height);
            }
            int fps = options.optInt("fps", 0);
            if (fps > 0) {
                caps.maxFps = Math.min(DEFAULT_MAX_FPS, fps);
            }
            int bitrate = options.optInt("bitrate", 0);
            if (bitrate >= BITRATE_MIN) {
                caps.maxBitrate = Math.min(DEFAULT_MAX_BITRATE, bitrate);
            }
            return caps;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("maxWidth", maxWidth);
                json.put("maxHeight", maxHeight);
                json.put("maxFps", maxFps);
                json.put("minBitrate", minBitrate);
                json.put("maxBitrate", maxBitrate);
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    /**
     * 按车机请求的采集配置规划实际编码参数（p.e）。
     *
     * <p>注意两个与 CAST.md 表述不同、但以代码/JS 为准的点（详见 README「与 CAST.md 的差异」）：
     * <ol>
     *   <li>performance 档：只有芯片串命中 {@code s905l3ab} 才用“请求码率（夹到 2 MiB）”；
     *       其他机型用固定 2621440。手机上这个分支基本不会命中 → 行为等同固定 2.5 MiB。</li>
     *   <li>compatibility 档的 fps 规则是 {@code fps >= 15 ? min(24, fps) : 24}，
     *       不是 {@code max(15, min(24, fps))}；请求 0/10 fps 时会变成 24。</li>
     * </ol>
     */
    public static Plan plan(CastWire.CaptureConfig config, String tier, String chipset, Caps caps) {
        Caps limits = caps == null ? new Caps() : caps;
        String useTier = isKnownTier(tier) ? tier : DEFAULT_TIER;
        String useChipset = chipset == null ? CHIPSET_GENERIC : chipset;

        int width = align16(Math.max(16, Math.min(config.width, limits.maxWidth)));
        int height = align16(Math.max(16, Math.min(config.height, limits.maxHeight)));
        int codec = config.encoder == 0 ? CastWire.CODEC_AVC : config.encoder;
        int requestedFps = config.fps > 0 ? Math.min(config.fps, limits.maxFps) : 30;
        int requestedBitrate = config.bitrate > 0 ? config.bitrate : CastWire.DEFAULT_BITRATE;
        boolean extended = config.extended;

        int fps;
        int bitrate;
        int levelId;
        String levelName;
        boolean baseline = false;

        if (PERFORMANCE.equals(useTier)) {
            fps = 30;
            bitrate = CHIPSET_S905L3AB.equals(useChipset)
                    ? clampBitrate(requestedBitrate)
                    : BITRATE_PERFORMANCE_FIXED;
            levelId = LEVEL_31_ID;
            levelName = "3.1";
        } else if (BITRATE_SAFE.equals(useTier)) {
            fps = 30;
            bitrate = clampBitrate(requestedBitrate);
            levelId = LEVEL_31_ID;
            levelName = "3.1";
        } else if (COMPATIBILITY.equals(useTier)) {
            fps = requestedFps >= 15 ? Math.min(24, requestedFps) : 24;
            bitrate = clampBitrate(requestedBitrate);
            LevelRow row = pickLevelRow(width, height, fps);
            levelId = row.id;
            levelName = row.name;
            // 车机解码器对 High profile 支持参差，这一档强制 Baseline（p.e 里 z8 = true）
            baseline = true;
        } else if (BALANCED.equals(useTier)) {
            fps = 20;
            bitrate = BITRATE_BALANCED;
            levelId = LEVEL_31_ID;
            levelName = "3.1";
        } else {
            // resolution-safe / decoder-safe：先等比缩到 800x480 以内，再按宏块数收敛
            double scale = Math.min(1.0d, Math.min(SAFE_MAX_WIDTH / (double) width,
                    SAFE_MAX_HEIGHT / (double) height));
            int outWidth = align16(Math.max(16, (int) Math.floor(width * scale)));
            int outHeight = align16(Math.max(16, (int) Math.floor(height * scale)));
            while (macroblocks(outWidth, outHeight) > MAX_MACROBLOCKS
                    && outWidth > 16 && outHeight > 16) {
                if (outWidth >= outHeight) {
                    outWidth -= ALIGN;
                } else {
                    outHeight -= ALIGN;
                }
            }
            width = outWidth;
            height = outHeight;
            fps = RESOLUTION_SAFE.equals(useTier) ? 20 : 15;
            bitrate = RESOLUTION_SAFE.equals(useTier) ? BITRATE_BALANCED : BITRATE_DECODER_SAFE;
            levelId = LEVEL_30_ID;
            levelName = "3.0";
        }

        // 上限只压不抬：fps 与码率都不允许超过 options 给的上限
        fps = Math.max(1, Math.min(fps, limits.maxFps));
        bitrate = Math.max(limits.minBitrate, Math.min(bitrate, limits.maxBitrate));
        return new Plan(useTier, width, height, fps, bitrate, codec, levelId, levelName,
                baseline, extended);
    }

    /** 下一档；已是最后一档返回 null（p.d.f）。 */
    public static String nextTier(String tier) {
        int index = indexOf(tier);
        if (index < 0 || index + 1 >= TIERS.length) {
            return null;
        }
        return TIERS[index + 1];
    }

    /** 档位下标；非法返回 -1。 */
    public static int indexOf(String tier) {
        if (tier == null) {
            return -1;
        }
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i].equals(tier)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isKnownTier(String tier) {
        return indexOf(tier) >= 0;
    }

    /**
     * 媒体连接是否该降档重试（p.f / r1.O0）。
     *
     * <p>条件：用户没主动停、还有下一档、重试次数没超，并且这次**开始推流不到 15 s 就断了**
     * 且确实发过帧（{@code sentFrames > 0}）。
     *
     * <p>为什么是“不到 15 s”而不是“跑满 15 s”：车机解码不兼容的表现是**连上后很快崩**，
     * 稳定跑过 15 s 再断通常是车机侧主动结束（用户退出投屏页），此时降档没有意义。
     * 跑满 15 s + 150 帧的判定属于「档位学习」，见 {@link #shouldLearnTier}。
     */
    public static boolean shouldDowngrade(String tier, int retries, long elapsedMs, int sentFrames,
                                          boolean stopped) {
        if (stopped) {
            return false;
        }
        if (nextTier(tier) == null) {
            return false;
        }
        if (retries >= MAX_RETRIES) {
            return false;
        }
        return elapsedMs >= 0 && elapsedMs < LEARN_THRESHOLD_MS && sentFrames > 0;
    }

    /**
     * 是否该把当前档位记成“这台车能跑”（p.b → compatibility-learned）。
     *
     * <p>只影响下次会话的首选档位，不改变当前会话（CAST.md §8.1）。
     */
    public static boolean shouldLearnTier(long elapsedMs, int sentFrames, boolean stopped) {
        if (stopped) {
            return false;
        }
        return elapsedMs >= LEARN_THRESHOLD_MS && sentFrames >= LEARN_THRESHOLD_FRAMES;
    }

    /** 尺寸按 16 对齐且不小于 16（p.a）。 */
    public static int align16(int value) {
        return Math.max(16, value & -ALIGN);
    }

    /** 宏块总数：ceil(w/16) * ceil(h/16)（p.c）。 */
    public static int macroblocks(int width, int height) {
        return ((width + ALIGN - 1) / ALIGN) * ((height + ALIGN - 1) / ALIGN);
    }

    /** 请求码率的夹取（p.i / JS clampBitrate）：区间外一律回落到 2 MiB。 */
    public static int clampBitrate(int requested) {
        if (requested >= BITRATE_MIN && requested <= BITRATE_MAX) {
            return Math.min(CastWire.DEFAULT_BITRATE, requested);
        }
        return CastWire.DEFAULT_BITRATE;
    }

    /**
     * 芯片标记（p.e 的 {@code v.b()}）。
     *
     * <p>〔未验证〕Motexa 里这个值来自它自己的设备指纹工具类，无法确认取的是
     * {@code Build.HARDWARE} 还是别的字段。这里用 HARDWARE/BOARD 的并集做包含匹配：
     * 只影响 performance 档的码率分支，最坏情况是“本该用 2.5 MiB 却用了请求值”，
     * 因此允许调用方通过 options.chipset 覆盖。
     */
    public static String detectChipset() {
        // v.d()：MODEL/DEVICE/PRODUCT/BOARD/HARDWARE 任一命中即算（CAST.md §8）
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(lower(android.os.Build.MODEL)).append(' ');
        fingerprint.append(lower(android.os.Build.DEVICE)).append(' ');
        fingerprint.append(lower(android.os.Build.PRODUCT)).append(' ');
        fingerprint.append(lower(android.os.Build.BOARD)).append(' ');
        fingerprint.append(lower(android.os.Build.HARDWARE));
        if (fingerprint.toString().contains("s905l3a")) {
            return CHIPSET_S905L3AB;
        }
        return CHIPSET_GENERIC;
    }

    /** 发送端本机是不是 S905L3A 平台（决定要不要加那组高性能编码键，见 CAST.md §8）。 */
    public static boolean isS905L3aSender() {
        return CHIPSET_S905L3AB.equals(detectChipset());
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    /* ================= level 查表（p.d） ================= */

    /** 一行 level 定义：id（AVCLevel 编码值）、名字、最大宏块数、最大“宏块×fps”。 */
    private static final class LevelRow {
        final int id;
        final String name;
        final int maxMacroblocks;
        final int maxLoad;

        LevelRow(int id, String name, int maxMacroblocks, int maxLoad) {
            this.id = id;
            this.name = name;
            this.maxMacroblocks = maxMacroblocks;
            this.maxLoad = maxLoad;
        }
    }

    /** 官方 H.264 level 上限表（p.d 的 14 行，最后一行封顶）。 */
    private static final LevelRow[] LEVELS = new LevelRow[]{
            new LevelRow(1, "1.0", 99, 1485),
            new LevelRow(4, "1.1", 396, 3000),
            new LevelRow(8, "1.2", 396, 6000),
            new LevelRow(16, "1.3", 396, 11880),
            new LevelRow(64, "2.1", 792, 19800),
            new LevelRow(128, "2.2", 1620, 20250),
            new LevelRow(256, "3.0", 1620, 40500),
            new LevelRow(512, "3.1", 3600, 108000),
            new LevelRow(1024, "3.2", 5120, 216000),
            new LevelRow(2048, "4.0", 8192, 245760),
            new LevelRow(8192, "4.2", 8704, 522240),
            new LevelRow(16384, "5.0", 22080, 589824),
            new LevelRow(32768, "5.1", 36864, 983040),
            new LevelRow(65536, "5.2", 36864, 2073600)
    };

    static LevelRow pickLevelRow(int width, int height, int fps) {
        int macroblocks = macroblocks(width, height);
        long load = (long) macroblocks * (long) Math.max(1, fps);
        for (int i = 0; i < LEVELS.length; i++) {
            LevelRow row = LEVELS[i];
            if (macroblocks <= row.maxMacroblocks && load <= row.maxLoad) {
                return row;
            }
        }
        return LEVELS[LEVELS.length - 1];
    }

    /** level id（AVCLevel 编码值）。 */
    public static int pickLevelId(int width, int height, int fps) {
        return pickLevelRow(width, height, fps).id;
    }

    /** level 名，例如 "3.1"。 */
    public static String pickLevelName(int width, int height, int fps) {
        return pickLevelRow(width, height, fps).name;
    }
}
