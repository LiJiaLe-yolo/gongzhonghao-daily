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

    // ========= 参数调整：调大max_tokens，降低temperature，提升输出确定性、支持更长文本 =========
    private static final int MAX_TOKENS = 8192;
    private static final double TEMPERATURE = 0.12;
    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;
    // 题材黑名单，过滤恐怖惊悚类，避免标签与影片严重错位
    private static final String[] MOVIE_BLACKLIST_KEYWORD = {"鬼玩人", "鬼", "驱魔", "电锯", "惊魂", "恐怖", "惊悚"};
    private static final String[] FILM_TAGS = {
            "现实扎心、人间百态",
            "人性深度、自我救赎",
            "青春成长、遗憾治愈",
            "社会讽刺、现实隐喻",
            "温情治愈、治愈内耗"
    };
    // ========== 修改后的Prompt模板：移除封面、配图、自查报告，仅公众号爆款影评 ==========
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
    // 兜底降级Prompt，重试后期，防幻觉、去AI味
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
    // 保存当前选中影片的TMDB完整详情
    private static TmdbMovieInfo currentTmdbMovieInfo = null;

    public static class ReviewResult {
        public String centralArgument;
        public List<String> titles;
        public String article;
    }

    // 自定义异常：影片模型无法处理，需要重新选片
    public static class MovieCannotHandleException extends Exception {
        public MovieCannotHandleException(String msg) {
            super(msg);
        }
    }

    // TMDB影片信息DTO
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
            // 在main层循环选片，遇到无法处理影片就重新选
            for (int attempt = 0; attempt < PICK_MAX_RETRY; attempt++) {
                currentTmdbMovieInfo = null;
                pickedMovie = pickOneMovie(usedMovies);
                System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);
                try {
                    reviewResult = generateReview(pickedMovie, currentTmdbMovieInfo != null ? currentTmdbMovieInfo.overview : null);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("[WARN] 当前影片[%s]模型无法处理，重新选片，msg=%s%n", pickedMovie, e.getMessage());
                    // 加入已使用，避免重复选中这部坏片
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
     * 选片主逻辑：
     * 1.优先TMDB now_playing+popular拿到候选集合，交给大模型从中挑选适合公众号影评的影片；
     * 2.大模型挑选不出合适，则按tag交给大模型拿经典片名，再回校TMDB搜索；
     * 3.TMDB完全失效走纯AI兜底
     * 返回电影中文片名，同时把完整tmdb信息写入静态变量currentTmdbMovieInfo
     */
    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.println("[LOG] 当前业务标签：" + currentFilmTag);
            // 阶段1：优先TMDB now_playing + popular获取候选池
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                List<TmdbMovieInfo> tmdbCandidateList = tryPickFromTmdbNowPlaying();
                if (!tmdbCandidateList.isEmpty()) {
                    // 过滤黑名单、已经使用过的影片
                    List<TmdbMovieInfo> filterCandidates = new ArrayList<>();
                    for (TmdbMovieInfo info : tmdbCandidateList) {
                        String title = info.title != null ? info.title : info.originalTitle;
                        if (!isBlackMovie(title) && !used.contains(title)) {
                            filterCandidates.add(info);
                        }
                    }
                    if (!filterCandidates.isEmpty()) {
                        // 交给大模型从候选池选出最合适的影片id
                        Long selectMovieId = aiSelectBestFilmFromTmdbCandidates(filterCandidates, currentFilmTag);
                        if (selectMovieId != null) {
                            // 找到选中的影片
                            for (TmdbMovieInfo info : filterCandidates) {
                                if (info.id == selectMovieId) {
                                    currentTmdbMovieInfo = info;
                                    System.out.println("[SUCCESS]大模型从TMDB候选池选中影片：" + info.title);
                                    return info.title;
                                }
                            }
                        }
                        System.out.println("[INFO]大模型从TMDB候选池未选出合适影片，进入经典库分支");
                    }
                }
                // 阶段2：TMDB实时池大模型选不出合格，调用大模型获取经典候选片名
                List<String> classicCandidates = aiGetClassicMovieNamesByTag(currentFilmTag);
                for (String candidateName : classicCandidates) {
                    if (isBlackMovie(candidateName) || used.contains(candidateName)) {
                        continue;
                    }
                    // AI输出片名必须回调用TMDB搜索校验真实存在
                    TmdbMovieInfo searchInfo = tmdbSearchMovie(candidateName);
                    if (searchInfo == null) {
                        continue;
                    }
                    // 合法性校验：简介长度、评分
                    if (isValidTmdbMovie(searchInfo)) {
                        System.out.println("[SUCCESS] AI按tag产出候选，TMDB搜索校验通过：" + searchInfo.title);
                        currentTmdbMovieInfo = searchInfo;
                        return searchInfo.title;
                    }
                }
            }
            // TMDB完全不可用降级：纯AI生成（兜底，此时没有tmdb overview，风险高）
            System.out.println("[WARN] TMDB链路全部无可用，进入纯AI兜底选片");
            List<String> aiPool = aiGenerateTaggedMoviePool();
            List<String> safe = new ArrayList<>();
            for (String n : aiPool) {
                if (!isBlackMovie(n) && !used.contains(n)) {
                    safe.add(n);
                }
            }
            if (!safe.isEmpty()) {
                currentTmdbMovieInfo = null;
                return safe.get(0);
            }
        }
        throw new Exception("多次尝试找不到未使用安全电影，请扩充候选池或清理Gist记录");
    }

    /**
     * 大模型从TMDB候选列表挑选最适合公众号深度影评的影片
     * @param candidates 过滤后候选列表
     * @param tag 当前业务标签
     * @return 返回选中movieId，没有合适返回null
     */
    private static Long aiSelectBestFilmFromTmdbCandidates(List<TmdbMovieInfo> candidates, String tag) throws IOException {
        JSONArray jsonArr = new JSONArray();
        for (TmdbMovieInfo info : candidates) {
            JSONObject item = new JSONObject();
            item.put("id", info.id);
            item.put("title", info.title);
            item.put("vote_average", info.voteAverage);
            item.put("overview", info.overview);
            jsonArr.add(item);
        }
        String prompt = "你是公众号影视选题编辑。业务标签：【" + tag + "】\n" +
                "下面是一批候选电影JSON数组，请从中挑选**最适合写公众号深度影评**的一部。\n" +
                "筛选标准：\n" +
                "1.有话题度，适合挖掘人性、现实、情绪共鸣，不要纯爆米花爽片；\n" +
                "2.简介信息充足，有足够解读空间；\n" +
                "3.排除恐怖惊悚鬼怪题材；\n" +
                "4.输出要求：只输出JSON，格式 {\"selectedId\":数字}。\n" +
                "如果这批全部都不适合做公众号深度影评，则输出 {\"selectedId\":null}。\n" +
                "候选列表：\n" + jsonArr;
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp).trim();
        Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(resp);
        if (matcher.find()) {
            resp = matcher.group();
        }
        try {
            JSONObject jo = JSON.parseObject(resp);
            Object sid = jo.get("selectedId");
            if (sid == null) {
                return null;
            }
            return jo.getLong("selectedId");
        } catch (Exception e) {
            System.err.println("[WARN] aiSelectBestFilmFromTmdbCandidates解析失败:" + e.getMessage());
            return null;
        }
    }

    /**
     * 返回全部合法候选集合；
     * 集合内部排序：优先overview文本长度降序，其次评分降序，优先拿到简介更长的影片
     */
    private static List<TmdbMovieInfo> tryPickFromTmdbNowPlaying() {
        List<Long> idList = new ArrayList<>();
        // now_playing 最近热映
        try {
            HttpUrl nowPlayingUrl = HttpUrl.parse(TMDB_BASE + "/movie/now_playing")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            System.out.println("[LOG] TMDB now_playing(最近热映)接口调用 url=" + nowPlayingUrl);
            Request reqNow = new Request.Builder().url(nowPlayingUrl).get().build();
            try (Response resp = HTTP_CLIENT.newCall(reqNow).execute()) {
                System.out.println("[LOG] TMDB now_playing status=" + resp.code());
                if (resp.isSuccessful()) {
                    JSONObject json = JSON.parseObject(resp.body().string());
                    JSONArray results = json.getJSONArray("results");
                    if (results != null && !results.isEmpty()) {
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            long mid = obj.getLongValue("id");
                            double vote = obj.getDoubleValue("vote_average");
                            if (vote >= TMDB_MIN_VOTE) {
                                idList.add(mid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("now_playing请求异常:" + e.getMessage());
        }
        // popular补充
        if (idList.size() < 6) {
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                        .newBuilder()
                        .addQueryParameter("api_key", TMDB_API_KEY)
                        .addQueryParameter("language", "zh-CN")
                        .build();
                System.out.println("[LOG] TMDB popular接口调用 url=" + url);
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    System.out.println("[LOG] TMDB popular status=" + resp.code());
                    if (resp.isSuccessful()) {
                        JSONObject json = JSON.parseObject(resp.body().string());
                        JSONArray results = json.getJSONArray("results");
                        if (results != null && !results.isEmpty()) {
                            for (Object o : results) {
                                JSONObject obj = (JSONObject) o;
                                long mid = obj.getLongValue("id");
                                double vote = obj.getDoubleValue("vote_average");
                                if (vote >= TMDB_MIN_VOTE && !idList.contains(mid)) {
                                    idList.add(mid);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("popular请求异常:" + e.getMessage());
            }
        }
        // 逐个拿详情做合法性校验，收集全部合法候选
        List<TmdbMovieInfo> validList = new ArrayList<>();
        for (long mid : idList) {
            TmdbMovieInfo info = tmdbGetMovieDetail(mid);
            if (isValidTmdbMovie(info)) {
                validList.add(info);
            }
        }
        // 核心排序：overview长度降序（优先简介长），其次评分降序
        validList.sort(Comparator
                .comparingInt((TmdbMovieInfo m) -> m.overview.length()).reversed()
                .thenComparingDouble(m -> m.voteAverage).reversed()
        );
        System.out.printf("[LOG] TMDB热映有效候选池大小=%d，已按简介长度降序排序%n", validList.size());
        return validList;
    }

    /**
     * 大模型：根据tag输出真实经典电影片名列表，只输出JSON数组
     */
    private static List<String> aiGetClassicMovieNamesByTag(String tag) throws IOException {
        String prompt = "你是电影知识库。根据标签【" + tag + "】输出最多5部世界范围内真实公映过的高分经典电影中文片名。\n" +
                "硬性约束：\n" +
                "1.严禁编造虚构影片，片名准确；过滤恐怖、惊悚、鬼怪。\n" +
                "2.只输出纯净JSON字符串数组，不要任何解释、markdown、序号。\n" +
                "输出示例：[\"怦然心动\",\"肖申克的救赎\"]";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr;
        try {
            arr = JSON.parseArray(resp);
        } catch (Exception e) {
            System.err.println("[WARN] aiGetClassicMovieNamesByTag JSON解析失败，返回空集合");
            return new ArrayList<>();
        }
        return arr.toList(String.class);
    }

    /**
     * TMDB 根据片名搜索，取第一条结果，返回完整详情；无结果返回null
     */
    private static TmdbMovieInfo tmdbSearchMovie(String movieName) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/search/movie")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .addQueryParameter("query", movieName)
                    .build();
            System.out.println("[LOG] TMDB接口调用 url=" + url);
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                System.out.println("[LOG] TMDB search/movie status=" + resp.code() + ", query=" + movieName);
                if (!resp.isSuccessful()) {
                    return null;
                }
                JSONObject jo = JSON.parseObject(resp.body().string());
                JSONArray results = jo.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    return null;
                }
                JSONObject first = results.getJSONObject(0);
                long mid = first.getLongValue("id");
                return tmdbGetMovieDetail(mid);
            }
        } catch (Exception e) {
            System.err.println("tmdbSearchMovie异常:" + e.getMessage());
            return null;
        }
    }

    /**
     * 获取TMDB/movie/{id}完整详情
     */
    private static TmdbMovieInfo tmdbGetMovieDetail(long movieId) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/" + movieId)
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            System.out.println("[LOG] TMDB接口调用，movieId=" + movieId + ", url=" + url);
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                System.out.println("[LOG] TMDB movie detail status=" + resp.code() + ", movieId=" + movieId);
                if (!resp.isSuccessful()) {
                    return null;
                }
                String bodyStr = resp.body().string();
                String sample = bodyStr.length() > 500 ? bodyStr.substring(0, 500) + "..." : bodyStr;
                System.out.println("[DEBUG]TMDB响应片段 movieId=" + movieId + " : " + sample);
                JSONObject jo = JSON.parseObject(bodyStr);
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
            System.err.println("tmdbGetMovieDetail异常:" + e.getMessage());
            return null;
        }
    }

    /**
     * TMDB影片合法性校验
     */
    private static boolean isValidTmdbMovie(TmdbMovieInfo info) {
        if (info == null) return false;
        if (info.overview == null || info.overview.length() < 80) return false;
        if ((info.title == null || info.title.isBlank()) && (info.originalTitle == null || info.originalTitle.isBlank()))
            return false;
        if (info.voteAverage < TMDB_MIN_VOTE) return false;
        return true;
    }

    private static boolean isBlackMovie(String movieName) {
        if (movieName == null || movieName.isBlank()) return true;
        String cleanName = movieName.replaceAll("\\s+", "");
        for (String kw : MOVIE_BLACKLIST_KEYWORD) {
            if (cleanName.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // 判断字符串是否包含中文汉字，过滤纯英文片名
    private static boolean hasChineseChar(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        int tagIndex = ThreadLocalRandom.current().nextInt(FILM_TAGS.length);
        currentFilmTag = FILM_TAGS[tagIndex];
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部**真实上映**的高分电影中文片名。\n" +
                "硬性约束：\n" +
                "1.禁止编造不存在影片，片名必须准确；过滤恐怖、惊悚、鬼怪题材。\n" +
                "2.贴合标签调性，适合公众号深度影评，大众认知度高。\n" +
                "3.只输出纯净JSON数组，不要任何解释、序号。\n" +
                "输出：[\"电影1\",\"电影2\"]";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr;
        try {
            arr = JSON.parseArray(resp);
        } catch (Exception e) {
            System.err.println("[WARN] aiGenerateTaggedMoviePool JSON解析失败，返回空集合");
            return new ArrayList<>();
        }
        return arr.toList(String.class);
    }

    /**
     * 生成影评：传入tmdbOverview，如果为null，代表tmdb不可用降级模式
     */
    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        ReviewResult fallbackResult = null;
        int emptyCount = 0;
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("[LOG] 影评生成第%d轮重试%n", i + 1);
            String prompt;
            String safeOverview;
            if (tmdbOverview == null || tmdbOverview.isBlank()) {
                safeOverview = "";
            } else {
                safeOverview = tmdbOverview.replace("\"", "\\\"");
            }
            if (i < 2) {
                prompt = String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
            } else {
                prompt = String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
            }
            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt);
            } catch (IOException ex) {
                System.err.println("[ERROR] callDeepSeek IO异常：" + ex.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            contentRaw = stripCodeBlock(contentRaw).trim();
            // 截取真正json区间，过滤前后多余垃圾字符
            Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(contentRaw);
            if (matcher.find()) {
                contentRaw = matcher.group();
            }
            System.out.println("AI返回原始JSON片段：" + contentRaw.substring(0, Math.min(300, contentRaw.length())));
            if (contentRaw.isBlank()) {
                emptyCount++;
                System.out.printf("[WARN] AI返回为空，emptyCount=%d%n", emptyCount);
                sleepRandom(1200, 2500);
                // 连续多次空返回，判定影片无法处理，抛出异常让上层重新选片
                if (emptyCount >= 3) {
                    throw new MovieCannotHandleException("连续多次返回空content，该影片模型无法输出");
                }
                continue;
            }
            emptyCount = 0;
            if (!contentRaw.startsWith("{") || !contentRaw.endsWith("}")) {
                System.out.println("[WARN] JSON首尾括号不完整，丢弃本轮");
                sleepRandom(1200, 2500);
                continue;
            }
            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.out.printf("JSON解析失败，重试，err=%s%n", e.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            if (jo == null) {
                System.out.println("parseObject返回null，重试");
                sleepRandom(1200, 2500);
                continue;
            }
            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");
            // 校验仅剩的三个必填字段
            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                System.out.println("[WARN] JSON字段缺失，重试生成");
                sleepRandom(1200, 2500);
                continue;
            }
            // 清洗文章残留指令标记
            article = cleanAiArticle(article);
            ReviewResult temp = new ReviewResult();
            temp.centralArgument = centralArg;
            temp.titles = titleArr.toList(String.class);
            temp.article = article;
            int len = article.length();
            System.out.printf("[LOG]本轮稿件长度：%d，理想区间[%d,%d]，兜底下限%d%n", len, ARTICLE_IDEAL_MIN, ARTICLE_IDEAL_MAX, ARTICLE_SOFT_MIN);
            if (len >= ARTICLE_IDEAL_MIN && len <= ARTICLE_IDEAL_MAX) {
                System.out.println("[LOG]拿到理想长度稿件，直接返回");
                return temp;
            }
            if (len > ARTICLE_IDEAL_MAX) {
                System.out.printf("[WARN]稿件超长(超过2500)，直接丢弃，当前长度：%d%n", len);
                sleepRandom(1200, 2500);
                continue;
            }
            if (len >= ARTICLE_SOFT_MIN) {
                System.out.printf("未达到理想下限%d，当前长度：%d，作为兜底候选保存%n", ARTICLE_IDEAL_MIN, len);
                fallbackResult = temp;
            } else {
                System.out.printf("稿件过短直接丢弃，当前长度：%d%n", len);
            }
            sleepRandom(1200, 2500);
        }
        if (fallbackResult != null) {
            System.out.println("多次未拿到理想长度稿件，使用兜底稿件继续执行任务");
            return fallbackResult;
        }
        throw new Exception("多次生成无法得到符合长度的影评");
    }

    /**
     * 清洗AI输出文章：去除残留【】指令标记、多余换行
     */
    private static String cleanAiArticle(String text) {
        if (text == null) return "";
        // 移除【xxx】类残留指令块
        Pattern pattern = Pattern.compile("【[^】]*】");
        text = pattern.matcher(text).replaceAll("");
        // 连续换行压缩
        text = text.replaceAll("\\n{3,}", "\n\n");
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
        // 兼容 ```json ``` / ```markdown ``` / ``` 各种变体
        Pattern codePattern = Pattern.compile("^```[a-zA-Z0-9]*\\R(.*?)\\R```$", Pattern.DOTALL);
        Matcher matcher = codePattern.matcher(s);
        if (matcher.matches()) {
            s = matcher.group(1).trim();
        }
        return s.trim();
    }

    private static String callDeepSeek(String prompt) throws IOException {
        IOException lastEx = null;
        for (int r = 0; r <= DEEPSEEK_NET_RETRY; r++) {
            System.out.printf("[LOG] DeepSeek接口调用，第%d次请求%n", r + 1);
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
                System.out.println("[LOG] DeepSeek http status=" + resp.code());
                String raw = resp.body().string();
                System.out.println("[DEBUG]DeepSeek完整响应:" + raw);
                if (!resp.isSuccessful()) {
                    throw new IOException("DeepSeek调用失败 code=" + resp.code() + " body=" + raw);
                }
                JSONObject jo = JSON.parseObject(raw);
                JSONObject choice0 = jo.getJSONArray("choices").getJSONObject(0);
                JSONObject msgObj = choice0.getJSONObject("message");
                String modelContent = msgObj.getString("content");
                String reasoningContent = msgObj.getString("reasoning_content");
                // 打印reasoning_content，用于定位v4‑flash content为空但是思考正常的问题
                if (reasoningContent != null && !reasoningContent.isBlank()) {
                    System.out.println("[DEBUG]DeepSeek reasoning_content长度=" + reasoningContent.length());
                }
                // v4‑flash 兜底：content为空，优先校验reasoning_content是否为合法JSON，否则不直接降级
                if ((modelContent == null || modelContent.isBlank())) {
                    if (reasoningContent != null && !reasoningContent.isBlank()) {
                        String temp = stripCodeBlock(reasoningContent).trim();
                        Matcher jsonMatcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(temp);
                        if (jsonMatcher.find()) {
                            temp = jsonMatcher.group();
                            try {
                                JSON.parseObject(temp);
                                System.out.println("[WARN] content为空，降级使用reasoning_content作为业务JSON");
                                modelContent = temp;
                            } catch (Exception e) {
                                System.err.println("[WARN] reasoning_content不是合法JSON，放弃降级");
                                modelContent = "";
                            }
                        }
                    }
                }
                if (modelContent == null || modelContent.isBlank()) {
                    System.out.println("[WARN] DeepSeek接口调用成功，但返回message.content为空字符串");
                    return "";
                }
                String snippet = modelContent.substring(0, Math.min(600, modelContent.length()));
                System.out.println("[LOG]模型返回content片段：" + snippet);
                return modelContent.trim();
            } catch (IOException e) {
                lastEx = e;
                System.err.printf("DeepSeek网络调用异常，第%d次重试：%s%n", r + 1, e.getMessage());
            }
        }
        throw new IOException("DeepSeek网络重试耗尽", lastEx);
    }

    private static List<String> loadUsedFromGist() {
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            System.out.println("[LOG] Gist读取接口 status=" + resp.code());
            if (!resp.isSuccessful()) {
                System.err.println("读取Gist失败 " + resp.code());
                return new ArrayList<>();
            }
            JSONObject gist = JSON.parseObject(resp.body().string());
            JSONObject files = gist.getJSONObject("files");
            if (files == null || !files.containsKey(GIST_FILENAME)) {
                System.out.println("Gist内目标文件不存在，初始化空已使用列表");
                return new ArrayList<>();
            }
            JSONObject fileObj = files.getJSONObject(GIST_FILENAME);
            String content = fileObj.getString("content");
            return JSON.parseArray(content).toList(String.class);
        } catch (Exception e) {
            System.err.println("loadUsedFromGist异常：" + e.getMessage());
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
                System.out.println("[LOG] Gist更新接口 status=" + resp.code());
                if (!resp.isSuccessful()) {
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    System.err.printf("更新Gist失败 code=%d, resp=%s%n", resp.code(), respBody);
                    return;
                }
                System.out.println("Gist已更新，任务结束");
            }
        } catch (Exception e) {
            System.err.println("saveUsedToGist发生异常，跳过写入已处理列表：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 飞书卡片携带完整影评正文，对齐示例《后室》卡片样式
     */
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
        // 塞入完整影评正文，飞书md转义特殊字符
        mdSb.append(escapeLarkMd(reviewResult.article));

        // 飞书卡片payload防护：如果文本过大做截断，避免11310报错
        final int MAX_CARD_CONTENT = 26000;
        String fullContent = mdSb.toString();
        if(fullContent.length() > MAX_CARD_CONTENT){
            mdSb.setLength(0);
            mdSb.append(fullContent,0, MAX_CARD_CONTENT);
            mdSb.append("\n\n⚠️【正文过长，卡片已截断，完整内容存在程序内存】");
        }

        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", mdSb.toString())));

        // note 底部备注：原始正文字数、数据源标记
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
            System.out.println("[LOG]飞书卡片推送 http status=" + resp.code());
            if (!resp.isSuccessful()) {
                System.err.println("飞书卡片推送异常：code=" + resp.code());
            }
        }
    }

    /**
     * 飞书lark_md简单转义，避免特殊符号破坏卡片渲染
     */
    private static String escapeLarkMd(String s) {
        if (s == null) return "";
        return s.replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }
}
