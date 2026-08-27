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
    private static final int ARTICLE_IDEAL_MIN = 1800;
    private static final int ARTICLE_IDEAL_MAX = 2500;
    private static final int ARTICLE_SOFT_MIN = 1500;
    // ========= 参数配置 =========
    private static final int MAX_TOKENS = 8192;
    private static final double TEMPERATURE = 0.12;
    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    // TMDB 黑名单题材ID：恐怖、惊悚 永久过滤
    private static final int GENRE_HORROR = 27;
    private static final int GENRE_THRILLER = 53;

    // 片名关键词黑名单
    private static final String[] MOVIE_BLACKLIST_KEYWORD = {"鬼玩人", "鬼", "驱魔", "电锯", "惊魂", "恐怖", "惊悚"};

    // ====================== 【超全量拓展TAG池｜36个细分标签】 ======================
    // 全覆盖公众号爆款影评风格，细分6大维度，涵盖大众热门+小众文艺风，适配所有高分可影评影片
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
            "正文总汉字1800‑2500；不清楚的影片细节绝不编造，只返回JSON。";

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
            "5.正文1600‑2200汉字。\n" +
            "\n" +
            "✅输出JSON格式，禁止代码块、多余文字：\n" +
            "{\n" +
            "  \"centralArgument\":\"中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"正文markdown\"\n" +
            "}\n" +
            "\n" +
            "电影《%s》，风格标签【%s】。只输出JSON。";

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
                currentTmdbMovieInfo = null;
                currentFilmTag = "";
                pickedMovie = pickOneMovie(usedMovies);
                System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);
                try {
                    reviewResult = generateReview(pickedMovie, currentTmdbMovieInfo != null ? currentTmdbMovieInfo.overview : null);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("[WARN] 当前影片[%s]模型无法处理，重新选片，msg=%s%n", pickedMovie, e.getMessage());
                    usedMovies.add(pickedMovie);
                    currentTmdbMovieInfo = null;
                }
            }

            if (reviewResult == null) {
                throw new Exception("多次选片仍然无法产出影评");
            }
            int articleRawLength = reviewResult.article.length();
            System.out.println("中心论点：" + reviewResult.centralArgument);
            System.out.println("候选标题：" + reviewResult.titles);
            System.out.println("影评正文原始长度：" + articleRawLength);
            sendFeishuCard(pickedMovie, reviewResult, articleRawLength, currentTmdbMovieInfo != null);
            System.out.println("飞书卡片推送完成");
            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
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

    /**
     * 【升级版精准映射】TMDB影片类型自动匹配超全量TAG池
     * 适配全品类高分影片，新增文艺、生活感悟类标签适配逻辑，匹配更精准、风格更多样
     */
    private static String autoMapFilmTagByGenres(TmdbMovieInfo info) {
        if (info == null || info.genres == null || info.genres.isEmpty()) {
            return FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
        }

        List<Integer> genreIds = new ArrayList<>();
        info.genres.forEach(g -> genreIds.add(g.id));

        // TMDB类型ID对照表：18剧情 / 80犯罪 / 9648悬疑 / 10749爱情 / 35喜剧 / 10751家庭 / 12冒险 / 14奇幻
        // 优先匹配深度现实向（最适合公众号爆款）
        if (genreIds.contains(18) && genreIds.contains(80)) {
            return "社会讽刺、现实隐喻";
        }
        if (genreIds.contains(18) || genreIds.contains(9648)) {
            return "现实扎心、人间百态";
        }
        if (genreIds.contains(80)) {
            return "人性深度、善恶博弈";
        }

        // 爱情、青春成长类
        if (genreIds.contains(10749)) {
            return "青春成长、遗憾治愈";
        }

        // 家庭、温情治愈类
        if (genreIds.contains(10751)) {
            return "亲情羁绊、烟火人间";
        }

        // 轻喜剧、治愈类
        if (genreIds.contains(35)) {
            return "温情治愈、治愈内耗";
        }

        // 文艺感悟兜底标签池（新增小众高级风格，丰富文风）
        List<String> fallbackTags = new ArrayList<>();
        fallbackTags.add("人性深度、自我救赎");
        fallbackTags.add("底层生活、人间真实");
        fallbackTags.add("平凡人性、微光治愈");
        fallbackTags.add("成长取舍、直面人生");
        fallbackTags.add("人生百态、世事通透");
        fallbackTags.add("岁月沉淀、人间清醒");
        fallbackTags.add("市井烟火、平凡众生");

        return fallbackTags.get(ThreadLocalRandom.current().nextInt(fallbackTags.size()));
    }

    /**
     * 选片主逻辑
     * TMDB选片成功 = 自动打标
     * TMDB失效降级 = 从全量拓展TAG池随机标签选片
     */
    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
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
                        System.out.println("[INFO] TMDB候选池无合适影片，进入TAG经典库分支");
                    }
                }

                // 经典库分支：从全量拓展TAG池随机标签
                currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
                System.out.println("[LOG] 经典库选片，随机业务标签：" + currentFilmTag);
                List<String> classicCandidates = aiGetClassicMovieNamesByTag(currentFilmTag);
                for (String candidateName : classicCandidates) {
                    if (isBlackMovie(candidateName) || used.contains(candidateName)) continue;
                    TmdbMovieInfo searchInfo = tmdbSearchMovie(candidateName);
                    if (searchInfo == null) continue;
                    boolean isHorrorOrThriller = searchInfo.genres.stream()
                            .anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                    if (isHorrorOrThriller) continue;
                    if (isValidTmdbMovie(searchInfo)) {
                        currentTmdbMovieInfo = searchInfo;
                        System.out.println("[SUCCESS] TAG经典库选片成功：" + searchInfo.title);
                        return searchInfo.title;
                    }
                }
            }

            // 纯AI兜底：全量TAG池随机，风格极致多样化
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.println("[WARN] TMDB失效，AI兜底选片，标签：" + currentFilmTag);
            List<String> aiPool = aiGenerateTaggedMoviePool();
            List<String> safe = new ArrayList<>();
            for (String n : aiPool) {
                if (!isBlackMovie(n) && !used.contains(n)) safe.add(n);
            }
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
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp).trim();
        Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(resp);
        if (matcher.find()) resp = matcher.group();
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
        // 热映影片
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
                }
            }
        } catch (Exception e) {
            System.err.println("now_playing请求异常:" + e.getMessage());
        }

        // 热门影片补充
        if (idList.size() < 6) {
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
                    }
                }
            } catch (Exception e) {
                System.err.println("popular请求异常:" + e.getMessage());
            }
        }

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
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("[WARN] 经典影片获取失败");
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
                if (!resp.isSuccessful()) return null;
                JSONObject jo = JSON.parseObject(resp.body().string());
                JSONArray results = jo.getJSONArray("results");
                if (results == null || results.isEmpty()) return null;
                long mid = results.getJSONObject(0).getLongValue("id");
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
                if (!resp.isSuccessful()) return null;
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
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("[WARN] 兜底片库生成失败");
            return new ArrayList<>();
        }
    }

    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        ReviewResult fallbackResult = null;
        int emptyCount = 0;
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            String safeOverview = (tmdbOverview == null || tmdbOverview.isBlank()) ? "" : tmdbOverview.replace("\"", "\\\"");
            String prompt = i < 2
                    ? String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag)
                    : String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);

            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt);
            } catch (IOException ex) {
                sleepRandom(1200, 2500);
                continue;
            }

            contentRaw = stripCodeBlock(contentRaw).trim();
            Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(contentRaw);
            if (matcher.find()) contentRaw = matcher.group();

            if (contentRaw.isBlank()) {
                emptyCount++;
                if (emptyCount >= 3) throw new MovieCannotHandleException("连续空返回");
                sleepRandom(1200, 2500);
                continue;
            }

            if (!contentRaw.startsWith("{") || !contentRaw.endsWith("}")) {
                sleepRandom(1200, 2500);
                continue;
            }

            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                sleepRandom(1200, 2500);
                continue;
            }

            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");
            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                sleepRandom(1200, 2500);
                continue;
            }

            article = cleanAiArticle(article);
            ReviewResult temp = new ReviewResult();
            temp.centralArgument = centralArg;
            temp.titles = titleArr.toList(String.class);
            temp.article = article;
            int len = article.length();

            if (len >= ARTICLE_IDEAL_MIN && len <= ARTICLE_IDEAL_MAX) return temp;
            if (len >= ARTICLE_SOFT_MIN) fallbackResult = temp;

            sleepRandom(1200, 2500);
        }
        if (fallbackResult != null) return fallbackResult;
        throw new Exception("无合格影评稿件");
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

    private static String callDeepSeek(String prompt) throws IOException {
        IOException lastEx = null;
        for (int r = 0; r <= DEEPSEEK_NET_RETRY; r++) {
            JSONObject body = new JSONObject();
            body.put("model", DEEPSEEK_MODEL);
            body.put("max_tokens", MAX_TOKENS);
            body.put("temperature", TEMPERATURE);
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
                if (!resp.isSuccessful()) throw new IOException("接口请求失败：" + resp.code());
                JSONObject jo = JSON.parseObject(raw);
                JSONObject choice0 = jo.getJSONArray("choices").getJSONObject(0);
                JSONObject msgObj = choice0.getJSONObject("message");
                String modelContent = msgObj.getString("content");
                String reasoningContent = msgObj.getString("reasoning_content");

                if ((modelContent == null || modelContent.isBlank()) && reasoningContent != null && !reasoningContent.isBlank()) {
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
            if (!resp.isSuccessful()) return new ArrayList<>();
            JSONObject gist = JSON.parseObject(resp.body().string());
            JSONObject files = gist.getJSONObject("files");
            if (files == null || !files.containsKey(GIST_FILENAME)) return new ArrayList<>();
            String content = files.getJSONObject(GIST_FILENAME).getString("content");
            return JSON.parseArray(content).toList(String.class);
        } catch (Exception e) {
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
                }
            }
        } catch (Exception e) {
            System.err.println("Gist更新异常，跳过保存");
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
        Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(rb).build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("飞书卡片推送异常：code=" + resp.code());
            }
        }
    }

    private static String escapeLarkMd(String s) {
        if (s == null) return "";
        return s.replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }
}
