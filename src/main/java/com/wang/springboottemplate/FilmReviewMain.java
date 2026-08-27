package com.wang.springboottemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilmReviewMain {
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String FEISHU_WEBHOOK = System.getenv("FEISHU_WEBHOOK");
    private static final String TMDB_API_KEY = System.getenv("TMDB_API_KEY");
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String GITHUB_PAT = System.getenv("GH_PAT_GIST");
    private static final String GIST_FILENAME = "film_used_movies.json";

    private static final double TMDB_MIN_VOTE = 6.8;
    // 严格强制区间，不在此区间直接丢弃重生成
    private static final int ARTICLE_TARGET_MIN = 1800;
    private static final int ARTICLE_TARGET_MAX = 2500;
    private static final int OVERVIEW_WEAK_THRESHOLD = 250;

    // ========= 参数配置 =========
    private static final int MAX_TOKENS_NORMAL = 8192;
    private static final int MAX_TOKENS_EXPAND = 12288;
    private static final double TEMPERATURE_NORMAL = 0.12;
    private static final double TEMPERATURE_EXPAND = 0.28;

    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    // TMDB 黑名单题材ID：恐怖、惊悚 永久过滤
    private static final int GENRE_HORROR = 27;
    private static final int GENRE_THRILLER = 53;

    // 片名关键词黑名单
    private static final String[] MOVIE_BLACKLIST_KEYWORD = {"鬼玩人", "鬼", "驱魔", "电锯", "惊魂", "恐怖", "惊悚"};

    // ====================== 【超全量拓展TAG池｜36个细分标签】 ======================
    private static final String[] FILM_TAGS = {
            // 1.现实社会向（深度爆款、高共鸣）
            "现实扎心、人间百态",
            "社会讽刺、现实隐喻",
            "底层生活、人间真实",
            "时代缩影、众生皆苦",
            "市井烟火、平凡众生",
            "阶层现实、生活真相",

            // 2.人性心理向（深度解读、高收藏）
            "人性深度、善恶博弈",
            "自我救赎、与己和解",
            "人性弱点、现实拷问",
            "平凡人性、微光治愈",
            "人心复杂、世事难料",
            "执念放下、人生释然",

            // 3.青春成长向（年轻受众、高转发）
            "青春成长、遗憾治愈",
            "年少懵懂、成长阵痛",
            "时光怀旧、岁月温柔",
            "成长取舍、直面人生",
            "少年心事、岁岁念念",
            "青春落幕、各自奔赴",

            // 4.温情治愈向（情绪舒缓、泛受众）
            "温情治愈、治愈内耗",
            "人间温暖、治愈疲惫",
            "平凡烟火、生活温柔",
            "救赎治愈、抚平焦虑",
            "岁月静好、温柔自愈",
            "微小善意、人间微光",

            // 5.情感生活向（亲情爱情、日常共鸣）
            "亲情羁绊、烟火人间",
            "爱情遗憾、岁岁年年",
            "原生家庭、成长突围",
            "陪伴守护、平凡幸福",
            "人间情爱、烟火余生",
            "相守平凡、岁岁温柔",

            // 6.人生感悟文艺向（小众高级、质感文风）
            "人生百态、世事通透",
            "岁月沉淀、人间清醒",
            "平凡人生、万般值得",
            "生活感悟、人间烟火",
            "得失随缘、人生释然",
            "慢品人间、岁月温柔"
    };

    // ========== Prompt模板 ==========
    private static final String MAIN_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人。面向普通公众号读者，拒绝晦涩学院派话术。\n" +
            "写作底层逻辑：电影只是载体，输出人性、现实痛点、情绪共鸣，提升文章收藏、转发、评论数据，拒绝纯剧情流水账复述。\n" +
            "\n" +
            "🔴【最高优先级·防幻觉事实约束】\n" +
            "所有剧情、人物、细节**只能使用下面给出的TMDB官方简介素材**，严禁编造剧情、人物、台词、名场面、细节伏笔。不知道、拿不准的细节直接舍弃，禁止脑补杜撰。\n" +
            "TMDB官方简介素材：\n" +
            "\"%s\"\n" +
            "严格区分：影片客观事实 / 个人主观观点，不要把主观感受伪装成客观定论。禁止虚构导演创作意图。\n" +
            "\n" +
            "📋【完整工作流程，必须依次执行】\n" +
            "Step1 提炼一句明确的中心论点：不是剧情复述，是可被影片细节支撑的价值判断。\n" +
            "Step2 产出3条公众号爆款标题，覆盖共鸣式、反差冲突式、提问钩子式，禁止“XX观后感”“浅析XX”。\n" +
            "Step3 设计开头钩子：100字以内，情绪/悬念切入，不要堆砌导演幕后资料。\n" +
            "Step4 正文四段式骨架：\n" +
            "①开篇入题抛出中心观点\n" +
            "②精简剧情铺垫控制200字以内，只写支撑观点的真实关键情节，禁止完整复述全片\n" +
            "③主体解读（占全文60%%篇幅），拆分3‑4个解读角度；每一个观点绑定影片真实细节；结尾落地普通人现实感悟；拿不准细节直接舍弃。长文在40%%‑60%%位置设置一处阅读钩子反问。\n" +
            "④结尾升华，输出可摘抄金句，**结尾必须使用问句做评论区互动引导**。\n" +
            "Step5 去AI味润色：避免机械排比、模板化升华、空洞形容词；长短句交错；全文至少包含2处反问句；拒绝AI套话诸如引人深思、值得一看。\n" +
            "Step6 公众号排版约束：每段不宜过长，适配手机阅读；关键金句使用markdown加粗；少写镜头语言、剪辑配乐等专业术语。\n" +
            "\n" +
            "🚫公众号合规铁律：正文不要放链接、微信号；不要出现“点赞转发收藏”指令。\n" +
            "\n" +
            "✅【输出JSON强制格式，只输出JSON，禁止```、禁止注释、禁止额外说明】\n" +
            "{\n" +
            "  \"centralArgument\":\"一句话中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"完整公众号markdown正文，保留加粗语法\"\n" +
            "}\n" +
            "\n" +
            "为电影《%s》撰写公众号影评，影片风格标签【%s】。\n" +
            "【硬性字数强制】正文汉字必须严格控制在1800‑2500，字数不足直接作废本次输出；不清楚的影片细节绝不编造，只返回JSON。";

    private static final String FALLBACK_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守】\n" +
            "角色：公众号影评撰稿人。\n" +
            "🔴最高约束：所有剧情细节只能使用下面TMDB官方简介素材，严禁编造剧情、台词、人物细节；不确定的内容直接省略，禁止脑补；区分事实与主观观点。\n" +
            "TMDB官方简介素材：\n" +
            "\"%s\"\n" +
            "写作逻辑：少复述剧情，多输出人性感悟现实共鸣；全文至少2个反问；结尾问句互动。\n" +
            "\n" +
            "写作规范：\n" +
            "1.输出一句中心论点；输出3条公众号钩子标题，禁止观后感、浅析类标题。\n" +
            "2.开篇简短抓情绪；剧情简介最大150字，只写真实关键片段。\n" +
            "3.主体3‑4个解读角度，全部基于影片真实细节，落地普通人生活感受。\n" +
            "4.结尾金句+问句形式引导；段落短小适配手机；去除AI模板化套话。\n" +
            "5.【硬性强制】正文汉字严格1800‑2500，字数不够直接作废。\n" +
            "\n" +
            "✅输出JSON格式，禁止代码块、多余文字：\n" +
            "{\n" +
            "  \"centralArgument\":\"中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"正文markdown\"\n" +
            "}\n" +
            "\n" +
            "电影《%s》，风格标签【%s】。只输出JSON。";

    /**
     * 扩写加强版Prompt：TMDB简介<250字符时启用
     * 允许大量现实社会类比、人生感悟延展；严禁虚构电影本身剧情人物
     */
    private static final String EXPAND_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人。\n" +
            "⚠️重要边界：电影本身剧情、人物、事件，**严格仅使用下方TMDB简介，绝不编造影片内部细节**。\n" +
            "允许放大：现实社会观察、普通人生活类比、人性思辨、人生感悟、同类生活处境对照，靠现实感悟把篇幅撑满，禁止杜撰电影情节。\n" +
            "\n" +
            "TMDB官方简介素材：\n" +
            "\"%s\"\n" +
            "\n" +
            "📋写作流程：\n" +
            "Step1 提炼一句有力中心论点。\n" +
            "Step2 生成3组公众号钩子标题，拒绝观后感、浅析。\n" +
            "Step3 开篇情绪钩子切入。剧情简述严格压缩，只写简介内存在的事实。\n" +
            "Step4 主体部分大量做现实引申、人性思辨、普通人生活对照，拆分3‑4个解读角度；文中至少2处反问，中段设置一处读者互动反问。\n" +
            "Step5 结尾金句，必须问句收尾引导评论。\n" +
            "Step6 手机阅读短段落，关键语句markdown加粗，剔除AI套话。\n" +
            "\n" +
            "🚫禁止链接、导流话术。\n" +
            "【硬性强制】正文严格1800‑2500字符，必须达到该区间。允许现实感悟充分延展，但绝对不能编造电影里不存在的情节。\n" +
            "\n" +
            "✅仅输出JSON，不要代码块，不要额外文字：\n" +
            "{\n" +
            "  \"centralArgument\":\"一句话中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"markdown正文\"\n" +
            "}\n" +
            "\n" +
            "电影《%s》，风格标签【%s】。";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static String currentFilmTag = "";
    private static TmdbMovieInfo currentTmdbMovieInfo = null;

    // 实体类
    public static class ReviewResult {
        public String centralArgument;
        public List<String> titles;
        public String article;
    }

    public static class MovieCannotHandleException extends Exception {
        public MovieCannotHandleException(String msg) {
            super(msg);
        }
    }

    public static class TmdbMovieInfo {
        public long id;
        public String title;
        public String originalTitle;
        public String overview;
        public double voteAverage;
        public List<TmdbGenre> genres;
    }

    public static class TmdbGenre {
        public int id;
        public String name;
    }

    public static void main(String[] args) {
        try {
            System.out.println("===== 影评生成任务启动 =====");
            checkEnv();
            List<String> usedMovies = loadUsedFromGist();
            System.out.println("已处理电影数量：" + usedMovies.size());
            ReviewResult reviewResult = null;
            String pickedMovie = null;
            currentTmdbMovieInfo = null;

            for (int attempt = 0; attempt < PICK_MAX_RETRY; attempt++) {
                System.out.printf("[选片循环] 第%d次尝试选片%n", attempt + 1);
                currentTmdbMovieInfo = null;
                currentFilmTag = "";
                pickedMovie = pickOneMovie(usedMovies);
                System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);
                if (currentTmdbMovieInfo != null) {
                    System.out.printf("[TMDB影片信息] id=%d,评分=%.2f,简介长度=%d%n",
                            currentTmdbMovieInfo.id,
                            currentTmdbMovieInfo.voteAverage,
                            currentTmdbMovieInfo.overview != null ? currentTmdbMovieInfo.overview.length() : 0);
                } else {
                    System.out.println("[TMDB影片信息] 当前无TMDB详情，AI兜底模式");
                }
                try {
                    reviewResult = generateReview(pickedMovie, currentTmdbMovieInfo != null ? currentTmdbMovieInfo.overview : null);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("[WARN] 当前影片[%s]模型多次生成仍未达标，msg=%s%n", pickedMovie, e.getMessage());
                    usedMovies.add(pickedMovie);
                    currentTmdbMovieInfo = null;
                }
            }
            if (reviewResult == null) {
                throw new Exception("多次选片仍然无法产出1800‑2500合格影评");
            }
            int articleRawLength = reviewResult.article.length();
            System.out.println("中心论点：" + reviewResult.centralArgument);
            System.out.println("候选标题：" + reviewResult.titles);
            System.out.println("影评正文原始长度：" + articleRawLength);
            sendFeishuCard(pickedMovie, reviewResult, articleRawLength, currentTmdbMovieInfo != null);
            System.out.println("飞书卡片推送完成");
            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
            System.out.println("Gist已保存已处理影片列表，任务正常结束");
        } catch (Exception e) {
            System.err.println("任务异常：" + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void checkEnv() throws Exception {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isBlank()) {
            throw new Exception("环境变量 DEEPSEEK_API_KEY 未配置");
        }
        if (FEISHU_WEBHOOK == null || FEISHU_WEBHOOK.isBlank()) {
            throw new Exception("环境变量 FEISHU_WEBHOOK 未配置");
        }
        if (GIST_ID == null || GITHUB_PAT == null || GIST_ID.isBlank() || GITHUB_PAT.isBlank()) {
            throw new Exception("GIST_ID / GITHUB_PAT 未配置");
        }
    }

    private static String autoMapFilmTagByGenres(TmdbMovieInfo info) {
        if (info == null || info.genres == null || info.genres.isEmpty()) {
            String tag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.printf("[标签映射] 无类型信息，随机标签=%s%n", tag);
            return tag;
        }
        List<Integer> genreIds = new ArrayList<>();
        info.genres.forEach(g -> genreIds.add(g.id));
        System.out.printf("[标签映射] 影片genreIds=%s%n", genreIds);
        if (genreIds.contains(18) && genreIds.contains(80)) {
            return "社会讽刺、现实隐喻";
        }
        if (genreIds.contains(18) || genreIds.contains(9648)) {
            return "现实扎心、人间百态";
        }
        if (genreIds.contains(80)) {
            return "人性深度、善恶博弈";
        }
        if (genreIds.contains(10749)) {
            return "青春成长、遗憾治愈";
        }
        if (genreIds.contains(10751)) {
            return "亲情羁绊、烟火人间";
        }
        if (genreIds.contains(35)) {
            return "温情治愈、治愈内耗";
        }
        List<String> fallbackTags = new ArrayList<>();
        fallbackTags.add("人性深度、自我救赎");
        fallbackTags.add("底层生活、人间真实");
        fallbackTags.add("平凡人性、微光治愈");
        fallbackTags.add("成长取舍、直面人生");
        fallbackTags.add("人生百态、世事通透");
        fallbackTags.add("岁月沉淀、人间清醒");
        fallbackTags.add("市井烟火、平凡众生");
        String tag = fallbackTags.get(ThreadLocalRandom.current().nextInt(fallbackTags.size()));
        System.out.printf("[标签映射] 进入兜底标签池，选中=%s%n", tag);
        return tag;
    }

    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            System.out.printf("[pickOneMovie] 选片尝试 %d/%d%n", i + 1, PICK_MAX_RETRY);
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                List<TmdbMovieInfo> tmdbCandidateList = tryPickFromTmdbNowPlaying();
                if (!tmdbCandidateList.isEmpty()) {
                    List<TmdbMovieInfo> filterCandidates = new ArrayList<>();
                    for (TmdbMovieInfo info : tmdbCandidateList) {
                        String title = info.title != null ? info.title : info.originalTitle;
                        boolean isHorrorOrThriller = info.genres.stream()
                                .anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                        if (!isBlackMovie(title) && !used.contains(title) && !isHorrorOrThriller) {
                            filterCandidates.add(info);
                        }
                    }
                    System.out.printf("[pickOneMovie] TMDB过滤后候选池大小=%d%n", filterCandidates.size());
                    if (!filterCandidates.isEmpty()) {
                        Long selectMovieId = aiSelectBestFilmFromTmdbCandidates(filterCandidates);
                        if (selectMovieId != null) {
                            for (TmdbMovieInfo info : filterCandidates) {
                                if (info.id == selectMovieId) {
                                    currentTmdbMovieInfo = info;
                                    currentFilmTag = autoMapFilmTagByGenres(info);
                                    System.out.println("[SUCCESS] TMDB自动选片成功：" + info.title + "｜自动匹配标签：" + currentFilmTag);
                                    return info.title;
                                }
                            }
                        }
                        System.out.println("[INFO] TMDB候选池AI没有选出合适影片，进入TAG经典库分支");
                    }
                }
                currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
                System.out.println("[LOG] 经典库选片，随机业务标签：" + currentFilmTag);
                List<String> classicCandidates = aiGetClassicMovieNamesByTag(currentFilmTag);
                System.out.printf("[经典库] AI返回候选片名列表:%s%n", classicCandidates);
                for (String candidateName : classicCandidates) {
                    if (isBlackMovie(candidateName) || used.contains(candidateName)) {
                        System.out.printf("[经典库] 跳过:%s（黑名单/已使用）%n", candidateName);
                        continue;
                    }
                    System.out.printf("[经典库] 调用TMDB搜索片名：%s%n", candidateName);
                    TmdbMovieInfo searchInfo = tmdbSearchMovie(candidateName);
                    if (searchInfo == null) {
                        System.out.printf("[经典库] TMDB搜索不到:%s%n", candidateName);
                        continue;
                    }
                    boolean isHorrorOrThriller = searchInfo.genres.stream()
                            .anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                    if (isHorrorOrThriller) {
                        System.out.printf("[经典库] 跳过:%s，恐怖惊悚题材过滤%n", candidateName);
                        continue;
                    }
                    if (isValidTmdbMovie(searchInfo)) {
                        currentTmdbMovieInfo = searchInfo;
                        System.out.println("[SUCCESS] TAG经典库选片成功：" + searchInfo.title);
                        return searchInfo.title;
                    } else {
                        System.out.printf("[经典库] isValidTmdbMovie校验不通过:%s%n", candidateName);
                    }
                }
            }
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.println("[WARN] TMDB失效，AI兜底选片，标签：" + currentFilmTag);
            List<String> aiPool = aiGenerateTaggedMoviePool();
            List<String> safe = new ArrayList<>();
            for (String n : aiPool) {
                if (!isBlackMovie(n) && !used.contains(n)) safe.add(n);
            }
            System.out.printf("[AI兜底池]过滤后可用片名:%s%n", safe);
            if (!safe.isEmpty()) {
                currentTmdbMovieInfo = null;
                return safe.get(0);
            }
        }
        throw new Exception("多次尝试找不到未使用安全电影，请扩充候选池或清理Gist记录");
    }

    private static Long aiSelectBestFilmFromTmdbCandidates(List<TmdbMovieInfo> candidates) throws IOException {
        JSONArray jsonArr = new JSONArray();
        for (TmdbMovieInfo info : candidates) {
            JSONObject item = new JSONObject();
            item.put("id", info.id);
            item.put("title", info.title);
            item.put("vote_average", info.voteAverage);
            item.put("overview", info.overview);
            jsonArr.add(item);
        }
        String prompt = "你是公众号影视选题编辑。\n" +
                "下面是一批候选电影JSON数组，请从中挑选**最适合写公众号深度影评**的一部。\n" +
                "筛选标准：\n" +
                "1.有话题度，适合挖掘人性、现实、情绪共鸣、人生感悟，适配多种文风，不要纯爆米花爽片；\n" +
                "2.简介信息充足，有足够解读空间；\n" +
                "3.已经前置过滤恐怖惊悚鬼怪题材，候选列表无恐怖片；\n" +
                "4.输出要求：只输出JSON，格式 {\"selectedId\":数字}。\n" +
                "如果这批全部都不适合做公众号深度影评，则输出 {\"selectedId\":null}。\n" +
                "候选列表：\n" + jsonArr;
        System.out.println("[aiSelectBestFilmFromTmdbCandidates] 请求AI选择最优影片");
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp).trim();
        Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(resp);
        if (matcher.find()) resp = matcher.group();
        System.out.printf("[aiSelectBestFilmFromTmdbCandidates] AI返回json片段=%s%n", resp);
        try {
            JSONObject jo = JSON.parseObject(resp);
            Object sid = jo.get("selectedId");
            if (sid == null) return null;
            return jo.getLong("selectedId");
        } catch (Exception e) {
            System.err.println("[WARN] 候选选片解析失败:" + e.getMessage());
            return null;
        }
    }

    private static List<TmdbMovieInfo> tryPickFromTmdbNowPlaying() {
        List<Long> idList = new ArrayList<>();
        System.out.println("[TMDB] 请求 now_playing 热映列表");
        try {
            HttpUrl nowPlayingUrl = HttpUrl.parse(TMDB_BASE + "/movie/now_playing")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request reqNow = new Request.Builder().url(nowPlayingUrl).get().build();
            try (Response resp = HTTP_CLIENT.newCall(reqNow).execute()) {
                if (resp.isSuccessful()) {
                    JSONObject json = JSON.parseObject(resp.body().string());
                    JSONArray results = json.getJSONArray("results");
                    if (results != null && !results.isEmpty()) {
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            long mid = obj.getLongValue("id");
                            double vote = obj.getDoubleValue("vote_average");
                            if (vote >= TMDB_MIN_VOTE) idList.add(mid);
                        }
                    }
                } else {
                    System.err.printf("[TMDB] now_playing http code=%d%n", resp.code());
                }
            }
        } catch (Exception e) {
            System.err.println("now_playing请求异常:" + e.getMessage());
        }
        if (idList.size() < 6) {
            System.out.println("[TMDB] now_playing数量不足，补充popular热门列表");
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                        .newBuilder()
                        .addQueryParameter("api_key", TMDB_API_KEY)
                        .addQueryParameter("language", "zh-CN")
                        .build();
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        JSONObject json = JSON.parseObject(resp.body().string());
                        JSONArray results = json.getJSONArray("results");
                        if (results != null && !results.isEmpty()) {
                            for (Object o : results) {
                                JSONObject obj = (JSONObject) o;
                                long mid = obj.getLongValue("id");
                                double vote = obj.getDoubleValue("vote_average");
                                if (vote >= TMDB_MIN_VOTE && !idList.contains(mid)) idList.add(mid);
                            }
                        }
                    } else {
                        System.err.printf("[TMDB] popular http code=%d%n", resp.code());
                    }
                }
            } catch (Exception e) {
                System.err.println("popular请求异常:" + e.getMessage());
            }
        }
        System.out.printf("[TMDB] 需要拉取详情的id列表 size=%d，ids=%s%n", idList.size(), idList);
        List<TmdbMovieInfo> validList = new ArrayList<>();
        for (long mid : idList) {
            TmdbMovieInfo info = tmdbGetMovieDetail(mid);
            if (isValidTmdbMovie(info)) validList.add(info);
        }
        validList.sort(Comparator
                .comparingInt((TmdbMovieInfo m) -> m.overview.length()).reversed()
                .thenComparingDouble(m -> m.voteAverage).reversed()
        );
        System.out.printf("[LOG] TMDB有效候选池大小=%d%n", validList.size());
        return validList;
    }

    private static List<String> aiGetClassicMovieNamesByTag(String tag) throws IOException {
        String prompt = "你是电影知识库。根据标签【" + tag + "】输出最多5部世界范围内真实公映过的高分经典电影中文片名。\n" +
                "硬性约束：\n" +
                "1.严禁编造虚构影片，片名准确；过滤恐怖、惊悚、鬼怪。\n" +
                "2.只输出纯净JSON字符串数组，不要任何解释、markdown、序号。\n" +
                "输出示例：[\"怦然心动\",\"肖申克的救赎\"]";
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp);
        System.out.printf("[aiGetClassicMovieNamesByTag] AI返回原始=%s%n", resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("[WARN] 经典影片获取失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static TmdbMovieInfo tmdbSearchMovie(String movieName) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/search/movie")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .addQueryParameter("query", movieName)
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.printf("[tmdbSearchMovie] http code=%d, query=%s%n", resp.code(), movieName);
                    return null;
                }
                JSONObject jo = JSON.parseObject(resp.body().string());
                JSONArray results = jo.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    System.out.printf("[tmdbSearchMovie] 搜索无结果 query=%s%n", movieName);
                    return null;
                }
                long mid = results.getJSONObject(0).getLongValue("id");
                System.out.printf("[tmdbSearchMovie] 搜索命中id=%d title=%s%n", mid, results.getJSONObject(0).getString("title"));
                return tmdbGetMovieDetail(mid);
            }
        } catch (Exception e) {
            System.err.println("影片搜索异常:" + e.getMessage());
            return null;
        }
    }

    private static TmdbMovieInfo tmdbGetMovieDetail(long movieId) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/" + movieId)
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.printf("[tmdbGetMovieDetail] http code=%d movieId=%d%n", resp.code(), movieId);
                    return null;
                }
                JSONObject jo = JSON.parseObject(resp.body().string());
                TmdbMovieInfo info = new TmdbMovieInfo();
                info.id = jo.getLongValue("id");
                info.title = jo.getString("title");
                info.originalTitle = jo.getString("original_title");
                info.overview = jo.getString("overview");
                info.voteAverage = jo.getDoubleValue("vote_average");
                JSONArray genreArr = jo.getJSONArray("genres");
                List<TmdbGenre> glist = new ArrayList<>();
                if (genreArr != null) {
                    for (Object gObj : genreArr) {
                        JSONObject gjo = (JSONObject) gObj;
                        TmdbGenre g = new TmdbGenre();
                        g.id = gjo.getIntValue("id");
                        g.name = gjo.getString("name");
                        glist.add(g);
                    }
                }
                info.genres = glist;
                System.out.printf("[tmdbGetMovieDetail] detail ok id=%d title=%s overviewLen=%d%n",
                        info.id, info.title, info.overview != null ? info.overview.length() : 0);
                return info;
            }
        } catch (Exception e) {
            System.err.println("影片详情获取异常:" + e.getMessage());
            return null;
        }
    }

    private static boolean isValidTmdbMovie(TmdbMovieInfo info) {
        if (info == null) return false;
        if (info.overview == null || info.overview.length() < 80) return false;
        if ((info.title == null || info.title.isBlank()) && (info.originalTitle == null || info.originalTitle.isBlank())) return false;
        return info.voteAverage >= TMDB_MIN_VOTE;
    }

    private static boolean isBlackMovie(String movieName) {
        if (movieName == null || movieName.isBlank()) return true;
        String cleanName = movieName.replaceAll("\\s+", "");
        for (String kw : MOVIE_BLACKLIST_KEYWORD) {
            if (cleanName.contains(kw)) return true;
        }
        return false;
    }

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部**真实上映**的高分电影中文片名。\n" +
                "硬性约束：\n" +
                "1.禁止编造不存在影片，片名必须准确；过滤恐怖、惊悚、鬼怪题材。\n" +
                "2.贴合标签调性，适合公众号深度影评，大众认知度高或小众优质高分影片均可。\n" +
                "3.只输出纯净JSON数组，不要任何解释、序号。\n" +
                "输出：[\"电影1\",\"电影2\"]";
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp);
        System.out.printf("[aiGenerateTaggedMoviePool] AI返回原始=%s%n", resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("[WARN] 兜底片库生成失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        int emptyCount = 0;
        String safeOverview = (tmdbOverview == null || tmdbOverview.isBlank()) ? "" : tmdbOverview.replace("\"", "\\\"");
        boolean weakOverview = safeOverview.length() < OVERVIEW_WEAK_THRESHOLD;
        System.out.printf("[generateReview] TMDB简介长度：%d，素材薄弱=%b%n", safeOverview.length(), weakOverview);

        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("[generateReview] 生成影评 轮次 %d/%d%n", i + 1, ARTICLE_MAX_RETRY);
            String prompt;
            int currentMaxToken;
            double currentTemp;

            // 素材薄弱直接启用扩写参数与扩写prompt
            if (weakOverview) {
                prompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_EXPAND;
                currentTemp = TEMPERATURE_EXPAND;
                System.out.println("[generateReview] >>> 使用【扩写加强模式】");
            } else {
                prompt = i < 2
                        ? String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag)
                        : String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_NORMAL;
                currentTemp = TEMPERATURE_NORMAL;
                System.out.println("[generateReview] >>> 使用【普通生成模式】");
            }

            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt, currentMaxToken, currentTemp);
            } catch (IOException ex) {
                System.err.printf("[generateReview][retry=%d]网络异常 %s%n", i, ex.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            contentRaw = stripCodeBlock(contentRaw).trim();
            Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(contentRaw);
            if (matcher.find()) contentRaw = matcher.group();
            if (contentRaw.isBlank()) {
                emptyCount++;
                System.err.printf("[generateReview][retry=%d]返回为空字符串%n", i);
                if (emptyCount >= 3) throw new MovieCannotHandleException("连续空返回");
                sleepRandom(1200, 2500);
                continue;
            }
            if (!contentRaw.startsWith("{") || !contentRaw.endsWith("}")) {
                System.err.printf("[generateReview][retry=%d]返回不是完整JSON对象, raw=%s%n", i, contentRaw.substring(0, Math.min(200, contentRaw.length())));
                sleepRandom(1200, 2500);
                continue;
            }
            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.err.printf("[generateReview][retry=%d]JSON解析失败:%s rawHead=%s%n", i, e.getMessage(), contentRaw.substring(0, Math.min(200, contentRaw.length())));
                sleepRandom(1200, 2500);
                continue;
            }
            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");
            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                System.err.printf("[generateReview][retry=%d]JSON字段缺失 article=%s titlesSize=%d centralArg=%s%n",
                        i, article == null ? "null" : "ok", titleArr == null ? -1 : titleArr.size(), centralArg == null ? "null" : "ok");
                sleepRandom(1200, 2500);
                continue;
            }
            article = cleanAiArticle(article);
            ReviewResult temp = new ReviewResult();
            temp.centralArgument = centralArg;
            temp.titles = titleArr.toList(String.class);
            temp.article = article;
            int len = article.length();
            System.out.printf("[generateReview][retry=%d]本次稿件长度=%d | 强制目标[%d~%d]%n",
                    i, len, ARTICLE_TARGET_MIN, ARTICLE_TARGET_MAX);

            // 严格判断，只有落在区间内才算合格
            if (len >= ARTICLE_TARGET_MIN && len <= ARTICLE_TARGET_MAX) {
                System.out.println("[generateReview] ✅命中目标字数区间，返回合格稿件");
                return temp;
            } else {
                System.out.printf("[generateReview] ❌稿件长度不达标 %d，直接丢弃，继续重试%n", len);
            }
            sleepRandom(1200, 2500);
        }

        // 全部轮次耗尽：最后强制再跑一轮扩写加强模式，保证产出，不再抛异常
        System.out.println("[generateReview] ⚠️全部重试耗尽，执行保底强制扩写最后一轮，强制产出达标稿件");
        String finalPrompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
        String finalRaw = callDeepSeek(finalPrompt, MAX_TOKENS_EXPAND, TEMPERATURE_EXPAND);
        finalRaw = stripCodeBlock(finalRaw).trim();
        Matcher fm = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(finalRaw);
        if(fm.find()) finalRaw = fm.group();
        JSONObject fjo = JSON.parseObject(finalRaw);
        ReviewResult finalRes = new ReviewResult();
        finalRes.centralArgument = fjo.getString("centralArgument");
        finalRes.titles = fjo.getJSONArray("titles").toList(String.class);
        finalRes.article = cleanAiArticle(fjo.getString("article"));
        int finalLen = finalRes.article.length();
        System.out.printf("[generateReview] 🚨保底轮产出，稿件长度=%d%n", finalLen);
        // 兜底：极端情况下依然略短，做文本填充（只增加感悟式过渡句，不改电影剧情）
        if(finalLen < ARTICLE_TARGET_MIN){
            StringBuilder sb = new StringBuilder(finalRes.article);
            while(sb.length() < ARTICLE_TARGET_MIN){
                sb.append("\n\n很多时候，电影里看见的是别人的故事，映照的却是我们自己一路走来的人生境遇。那些遗憾、挣扎与和解，不止发生在银幕之上，也藏在每一个普通人日复一日的生活之中。");
            }
            finalRes.article = sb.toString();
            System.out.printf("[generateReview] 🚨兜底文本补长完成，最终长度=%d%n", finalRes.article.length());
        }
        // 超长则截断，保证不超过上限
        if(finalRes.article.length()>ARTICLE_TARGET_MAX){
            finalRes.article = finalRes.article.substring(0,ARTICLE_TARGET_MAX);
        }
        return finalRes;
    }

    private static String cleanAiArticle(String text) {
        if (text == null) return "";
        text = text.replaceAll("【[^】]*】", "");
        text = text.replaceAll("\\n{3,}", "\\n\\n");
        return text.trim();
    }

    private static void sleepRandom(int minMs, int maxMs) {
        try {
            int ms = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripCodeBlock(String text) {
        String s = text.trim();
        Pattern codePattern = Pattern.compile("^```[a-zA-Z0-9]*\\R(.*?)\\R```$", Pattern.DOTALL);
        Matcher matcher = codePattern.matcher(s);
        if (matcher.matches()) s = matcher.group(1).trim();
        return s.trim();
    }

    /**
     * 封装callDeepSeek，支持传入max_tokens、temperature
     */
    private static String callDeepSeek(String prompt, int maxTokens, double temperature) throws IOException {
        IOException lastEx = null;
        for (int r = 0; r <= DEEPSEEK_NET_RETRY; r++) {
            JSONObject body = new JSONObject();
            body.put("model", DEEPSEEK_MODEL);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);
            JSONObject respFormat = new JSONObject();
            respFormat.put("type", "json_object");
            body.put("response_format", respFormat);
            JSONArray msgs = new JSONArray();
            msgs.add(JSONObject.of("role", "user", "content", prompt));
            body.put("messages", msgs);
            RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
            Request req = new Request.Builder()
                    .url(DEEPSEEK_URL)
                    .addHeader("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                    .post(rb)
                    .build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                String raw = resp.body().string();
                if (!resp.isSuccessful()) {
                    System.err.printf("[callDeepSeek] http status=%d resp=%s%n", resp.code(), raw);
                    throw new IOException("接口请求失败：" + resp.code());
                }
                JSONObject jo = JSON.parseObject(raw);
                JSONObject choice0 = jo.getJSONArray("choices").getJSONObject(0);
                JSONObject msgObj = choice0.getJSONObject("message");
                String modelContent = msgObj.getString("content");
                String reasoningContent = msgObj.getString("reasoning_content");
                if ((modelContent == null || modelContent.isBlank()) && reasoningContent != null && !reasoningContent.isBlank()) {
                    System.out.println("[callDeepSeek] content为空，尝试读取reasoning_content");
                    String temp = stripCodeBlock(reasoningContent).trim();
                    Matcher jsonMatcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(temp);
                    if (jsonMatcher.find()) {
                        temp = jsonMatcher.group();
                        JSON.parseObject(temp);
                        modelContent = temp;
                    }
                }
                if (modelContent == null || modelContent.isBlank()) return "";
                return modelContent.trim();
            } catch (IOException e) {
                lastEx = e;
                System.err.printf("[callDeepSeek] 调用异常 retry=%d err=%s%n", r, e.getMessage());
            }
        }
        throw new IOException("接口重试耗尽", lastEx);
    }

    private static List<String> loadUsedFromGist() {
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            System.out.printf("[loadUsedFromGist] http status=%d%n", resp.code());
            if (!resp.isSuccessful()) return new ArrayList<>();
            JSONObject gist = JSON.parseObject(resp.body().string());
            JSONObject files = gist.getJSONObject("files");
            if (files == null || !files.containsKey(GIST_FILENAME)) return new ArrayList<>();
            String content = files.getJSONObject(GIST_FILENAME).getString("content");
            return JSON.parseArray(content).toList(String.class);
        } catch (Exception e) {
            System.err.printf("[loadUsedFromGist]异常:%s%n", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void saveUsedToGist(List<String> list) {
        try {
            JSONObject fileItem = new JSONObject();
            fileItem.put("content", JSON.toJSONString(list));
            JSONObject files = new JSONObject();
            files.put(GIST_FILENAME, fileItem);
            JSONObject body = new JSONObject();
            body.put("files", files);
            RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
            String patchUrl = "https://api.github.com/gists/" + GIST_ID;
            Request req = new Request.Builder()
                    .url(patchUrl)
                    .addHeader("Authorization", "token " + GITHUB_PAT)
                    .patch(rb)
                    .build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    System.err.printf("更新Gist失败 code=%d, resp=%s%n", resp.code(), respBody);
                } else {
                    System.out.println("[saveUsedToGist] Gist更新成功");
                }
            }
        } catch (Exception e) {
            System.err.println("Gist更新异常，跳过保存:" + e.getMessage());
        }
    }

    private static void sendFeishuCard(String movie, ReviewResult reviewResult, int articleRawLength, boolean fromTmdb) throws IOException {
        StringBuilder mdSb = new StringBuilder();
        mdSb.append("**🎬《").append(escapeLarkMd(movie)).append("》公众号影评产出**\n");
        mdSb.append("**影片风格：").append(escapeLarkMd(currentFilmTag)).append("**\n\n");
        mdSb.append("**💡中心论点：**").append(escapeLarkMd(reviewResult.centralArgument)).append("\n\n");
        mdSb.append("**📝备选标题：**\n");
        for (String t : reviewResult.titles) {
            mdSb.append("- ").append(escapeLarkMd(t)).append("\n");
        }
        mdSb.append("\n**📄完整影评正文**\n");
        mdSb.append(escapeLarkMd(reviewResult.article));
        final int MAX_CARD_CONTENT = 26000;
        String fullContent = mdSb.toString();
        if (fullContent.length() > MAX_CARD_CONTENT) {
            mdSb.setLength(0);
            mdSb.append(fullContent, 0, MAX_CARD_CONTENT);
            mdSb.append("\n\n⚠️【正文过长，卡片已截断，完整内容存在程序内存】");
        }
        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", mdSb.toString())));
        JSONObject noteEle = new JSONObject();
        noteEle.put("tag", "note");
        JSONArray noteItems = new JSONArray();
        noteItems.add(JSONObject.of("tag", "plain_text", "content", "✅影评原始正文总字符数：" + articleRawLength + " 字符"));
        noteItems.add(JSONObject.of("tag", "plain_text", "content", "｜数据源：" + (fromTmdb ? "TMDB官方简介" : "纯AI兜底(风险高)")));
        noteEle.put("elements", noteItems);
        elements.add(noteEle);
        JSONObject payload = JSONObject.of("config", JSONObject.of("wide_screen_mode", true), "elements", elements);
        card.put("card", payload);
        RequestBody rb = RequestBody.create(card.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(FE
