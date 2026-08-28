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
    
    // ⚠️ 修复了之前代码中错误的 Markdown 链接格式 [url](url)
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String GITHUB_PAT = System.getenv("GH_PAT_GIST");
    private static final String GIST_FILENAME = "film_used_movies.json";

    private static final double TMDB_MIN_VOTE = 6.8;
    private static final int ARTICLE_TARGET_MIN = 1800;
    private static final int ARTICLE_TARGET_MAX = 2500;
    private static final int OVERVIEW_WEAK_THRESHOLD = 250;

    private static final int MAX_TOKENS_NORMAL = 8192;
    private static final int MAX_TOKENS_EXPAND = 12288;
    private static final double TEMPERATURE_NORMAL = 0.12;
    private static final double TEMPERATURE_EXPAND = 0.28;

    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    private static final int GENRE_HORROR = 27;
    private static final int GENRE_THRILLER = 53;

    private static final String[] MOVIE_BLACKLIST_KEYWORD = {"鬼玩人", "鬼", "驱魔", "电锯", "惊魂", "恐怖", "惊悚"};

    private static final String[] FILM_TAGS = {
            "现实扎心、人间百态", "社会讽刺、现实隐喻", "底层生活、人间真实", "时代缩影、众生皆苦",
            "市井烟火、平凡众生", "阶层现实、生活真相", "人性深度、善恶博弈", "自我救赎、与己和解",
            "人性弱点、现实拷问", "平凡人性、微光治愈", "人心复杂、世事难料", "执念放下、人生释然",
            "青春成长、遗憾治愈", "年少懵懂、成长阵痛", "时光怀旧、岁月温柔", "成长取舍、直面人生",
            "少年心事、岁岁念念", "青春落幕、各自奔赴", "温情治愈、治愈内耗", "人间温暖、治愈疲惫",
            "平凡烟火、生活温柔", "救赎治愈、抚平焦虑", "岁月静好、温柔自愈", "微小善意、人间微光",
            "亲情羁绊、烟火人间", "爱情遗憾、岁岁年年", "原生家庭、成长突围", "陪伴守护、平凡幸福",
            "人间情爱、烟火余生", "相守平凡、岁岁温柔", "人生百态、世事通透", "岁月沉淀、人间清醒",
            "平凡人生、万般值得", "生活感悟、人间烟火", "得失随缘、人生释然", "慢品人间、岁月温柔"
    };

    // ================= Prompt 模板 =================
    private static final String MAIN_REVIEW_PROMPT_TPL =
            "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人。面向普通公众号读者，拒绝晦涩学院派话术。\n" +
            "写作底层逻辑：电影只是载体，输出人性、现实痛点、情绪共鸣，提升文章收藏、转发数据，拒绝纯剧情流水账复述。\n" +
            "\n" +
            "🔴【最高优先级·防幻觉事实约束】\n" +
            "所有剧情、人物、细节只能使用下面给出的TMDB官方简介素材，严禁编造剧情、人物、台词、名场面、细节伏笔。不知道、拿不准的细节直接舍弃，禁止脑补杜撰。\n" +
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
            "④结尾升华，输出可摘抄金句；结尾使用一句有力的反问句引发读者内心思考，自然收束全文。\n" +
            "Step5 去AI味润色：避免机械排比、模板化升华、空洞形容词；长短句交错；全文至少包含2处反问句；拒绝AI套话诸如引人深思、值得一看。\n" +
            "Step6 公众号排版约束：每段不宜过长，适配手机阅读；必须将中心论点、核心金句、强烈情绪共鸣的句子使用 Markdown 的 **加粗** 语法进行高亮展示；少写镜头语言、剪辑配乐等专业术语。\n" +
            "\n" +
            "🚫公众号合规铁律：正文不要放链接、微信号；不要出现“点赞转发收藏”指令；严禁出现“评论区聊聊”“评论区等你”“欢迎留言”“你怎么看，欢迎讨论”等任何引导读者去评论区互动的套话。结尾的反问句仅用于引发读者内心思考，不要引导互动。\n" +
            "\n" +
            "✅【输出JSON强制格式，只输出JSON，禁止代码块、禁止注释、禁止额外说明】\n" +
            "{\n" +
            "  \"centralArgument\":\"一句话中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"完整公众号markdown正文，保留加粗语法\"\n" +
            "}\n" +
            "\n" +
            "为电影《%s》撰写公众号影评，影片风格标签【%s】。\n" +
            "【硬性字数强制】正文汉字必须严格控制在1800‑2500，字数不足直接作废本次输出；不清楚的影片细节绝不编造，只返回JSON。";

    private static final String FALLBACK_REVIEW_PROMPT_TPL =
            "【硬性强制规则，必须全部遵守】\n" +
            "角色：公众号影评撰稿人。\n" +
            "🔴最高约束：所有剧情细节只能使用下面TMDB官方简介素材，严禁编造剧情、台词、人物细节；不确定的内容直接省略，禁止脑补；区分事实与主观观点。\n" +
            "TMDB官方简介素材：\n" +
            "\"%s\"\n" +
            "写作逻辑：少复述剧情，多输出人性感悟现实共鸣；全文至少2个反问；结尾使用反问句引发思考。\n" +
            "\n" +
            "写作规范：\n" +
            "1.输出一句中心论点；输出3条公众号钩子标题，禁止观后感、浅析类标题。\n" +
            "2.开篇简短抓情绪；剧情简介最大150字，只写真实关键片段。\n" +
            "3.主体3‑4个解读角度，全部基于影片真实细节，落地普通人生活感受。\n" +
            "4.结尾金句+一句有力反问引发读者内心思考，自然收束；段落短小适配手机；去除AI模板化套话。\n" +
            "5.必须将核心金句、情绪共鸣点使用 Markdown 的 **加粗** 语法高亮。\n" +
            "6.【硬性强制】正文汉字严格1800‑2500，字数不够直接作废。\n" +
            "7.严禁出现“评论区聊聊”“评论区等你”“欢迎留言”等引导评论区互动的套话。结尾反问仅用于引发思考，不引导互动。\n" +
            "\n" +
            "✅输出JSON格式，禁止代码块、多余文字：\n" +
            "{\n" +
            "  \"centralArgument\":\"中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"正文markdown\"\n" +
            "}\n" +
            "\n" +
            "电影《%s》，风格标签【%s】。只输出JSON。";

    private static final String EXPAND_REVIEW_PROMPT_TPL =
            "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人。\n" +
            "⚠️重要边界：电影本身剧情、人物、事件，严格仅使用下方TMDB简介，绝不编造影片内部细节。\n" +
            "允许放大：现实社会观察、普通人生活类比、人性思辨、人生感悟、同类生活处境对照，靠现实感悟把篇幅撑满，禁止杜撰电影情节。\n" +
            "\n" +
            "TMDB官方简介素材：\n" +
            "\"%s\"\n" +
            "\n" +
            "📋写作流程：\n" +
            "Step1 提炼一句有力中心论点。\n" +
            "Step2 生成3组公众号钩子标题，拒绝观后感、浅析。\n" +
            "Step3 开篇情绪钩子切入。剧情简述严格压缩，只写简介内存在的事实。\n" +
            "Step4 主体部分大量做现实引申、人性思辨、普通人生活对照，拆分3‑4个解读角度；文中至少2处反问，中段设置一处读者内心反问。\n" +
            "Step5 结尾金句；结尾使用一句有力的反问句引发读者内心思考，自然收束全文。\n" +
            "Step6 手机阅读短段落，必须将中心论点、核心金句、强烈情绪共鸣的句子使用 Markdown 的 **加粗** 语法进行高亮展示，剔除AI套话。\n" +
            "\n" +
            "🚫禁止链接、导流话术；严禁出现“评论区聊聊”“评论区等你”“欢迎留言”等引导评论区互动的套话。结尾反问仅用于引发读者内心思考，不引导互动。\n" +
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

    public static class ReviewResult {
        public String centralArgument;
        public List<String> titles;
        public String article;
    }

    public static class MovieCannotHandleException extends Exception {
        public MovieCannotHandleException(String msg) { super(msg); }
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
            System.out.println("\n" + "=".repeat(60));
            System.out.println("🚀 影评生成任务启动");
            System.out.println("=".repeat(60));
            checkEnv();

            List<String> usedMovies = loadUsedFromGist();
            System.out.println("📊 已处理电影数量：" + usedMovies.size());

            ReviewResult reviewResult = null;
            String pickedMovie = null;
            currentTmdbMovieInfo = null;

            for (int attempt = 0; attempt < PICK_MAX_RETRY; attempt++) {
                currentTmdbMovieInfo = null;
                currentFilmTag = "";

                pickedMovie = pickOneMovie(usedMovies);
                System.out.printf("\n🎯 最终选中电影：《%s》｜ 风格标签：【%s】\n", pickedMovie, currentFilmTag);

                if (currentTmdbMovieInfo != null) {
                    System.out.printf("📖 [TMDB影片信息] id=%d, 评分=%.2f, 简介长度=%d\n",
                            currentTmdbMovieInfo.id, currentTmdbMovieInfo.voteAverage,
                            currentTmdbMovieInfo.overview != null ? currentTmdbMovieInfo.overview.length() : 0);
                } else {
                    System.out.println("⚠️ [TMDB影片信息] 当前无TMDB详情，将使用AI兜底模式生成");
                }

                try {
                    reviewResult = generateReview(pickedMovie, currentTmdbMovieInfo != null ? currentTmdbMovieInfo.overview : null);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("❌ [WARN] 当前影片《%s》模型多次生成仍未达标，msg=%s\n", pickedMovie, e.getMessage());
                    usedMovies.add(pickedMovie);
                    currentTmdbMovieInfo = null;
                }
            }

            if (reviewResult == null) {
                throw new Exception("多次选片仍然无法产出1800‑2500合格影评");
            }

            int articleRawLength = reviewResult.article.length();
            System.out.println("\n" + "=".repeat(60));
            System.out.println("✅ 影评生成成功！");
            System.out.println("=".repeat(60));
            System.out.println("💡 中心论点：" + reviewResult.centralArgument);
            System.out.println("🏷️ 候选标题：" + reviewResult.titles);
            System.out.println("📏 影评正文原始长度：" + articleRawLength + " 字符");

            sendFeishuCard(pickedMovie, reviewResult, articleRawLength);

            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
            System.out.println("\n🎉 任务正常结束，Gist已保存已处理影片列表！");

        } catch (Exception e) {
            System.err.println("\n💥 任务异常：" + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void checkEnv() throws Exception {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isBlank())
            throw new Exception("环境变量 DEEPSEEK_API_KEY 未配置");
        if (FEISHU_WEBHOOK == null || FEISHU_WEBHOOK.isBlank())
            throw new Exception("环境变量 FEISHU_WEBHOOK 未配置");
        if (GIST_ID == null || GITHUB_PAT == null || GIST_ID.isBlank() || GITHUB_PAT.isBlank())
            throw new Exception("GIST_ID / GITHUB_PAT 未配置");
    }

    // ==================== 核心选片逻辑 ====================
    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            System.out.println("\n" + "=".repeat(60));
            System.out.printf("🔄 [选片] 第 %d/%d 次尝试\n", i + 1, PICK_MAX_RETRY);
            System.out.println("=".repeat(60));

            // ========== 第一步：TMDB 拉取 (含热搜) + 大模型优选 ==========
            System.out.println("\n👉 【步骤一】从 TMDB 拉取热门/热搜影片，结合社会热点情绪让大模型挑选...");
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                List<TmdbMovieInfo> tmdbCandidates = fetchTmdbCandidates();
                System.out.printf("  📊 TMDB 拉取并校验后候选数量: %d\n", tmdbCandidates.size());

                List<TmdbMovieInfo> filtered = new ArrayList<>();
                for (TmdbMovieInfo info : tmdbCandidates) {
                    String title = info.title != null ? info.title : info.originalTitle;
                    boolean isHorrorOrThriller = info.genres != null && info.genres.stream()
                            .anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                    if (!isBlackMovie(title) && !used.contains(title) && !isHorrorOrThriller) {
                        filtered.add(info);
                    } else {
                        System.out.printf("  🚫 过滤: %s\n", title);
                    }
                }
                System.out.printf("  ✅ 过滤后有效候选: %d 部\n", filtered.size());

                if (!filtered.isEmpty()) {
                    System.out.println("  🤖 正在让大模型结合【近期社会热点/热搜情绪】从候选中挑选...");
                    Long selectedId = aiSelectBestFilm(filtered);
                    if (selectedId != null) {
                        for (TmdbMovieInfo info : filtered) {
                            if (info.id == selectedId) {
                                currentTmdbMovieInfo = info;
                                currentFilmTag = autoMapFilmTagByGenres(info);
                                System.out.printf("  🎉 大模型选中：《%s》| 标签：【%s】\n", info.title, currentFilmTag);
                                return info.title;
                            }
                        }
                    }
                    System.out.println("  ⚠️ 大模型认为当前候选池无合适影片");
                }
            } else {
                System.out.println("  ⚠️ 未配置 TMDB_API_KEY，跳过 TMDB 阶段");
            }

            // ========== 第二步：Tag + 大模型推荐经典片 (结合热点) + TMDB 验证 ==========
            System.out.println("\n👉 【步骤二】TMDB 无合适影片，走标签推荐策略（结合热点情绪）...");
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.printf("  🎲 随机标签: 【%s】\n", currentFilmTag);
            
            System.out.println("  🤖 正在让大模型推荐能切中当下社会痛点/热搜情绪的经典高分电影...");
            List<String> classicNames = aiGetClassicMovieNamesByTag(currentFilmTag);
            System.out.printf("  📜 大模型推荐: %s\n", classicNames);

            for (String name : classicNames) {
                if (isBlackMovie(name) || used.contains(name)) {
                    System.out.printf("  🚫 跳过: %s (黑名单/已用)\n", name);
                    continue;
                }
                System.out.printf("  🔍 TMDB 验证: %s\n", name);
                TmdbMovieInfo searchInfo = tmdbSearchMovie(name);
                if (searchInfo == null) {
                    System.out.printf("  ❌ TMDB 无结果: %s\n", name);
                    continue;
                }
                boolean isHorror = searchInfo.genres != null && searchInfo.genres.stream()
                        .anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                if (isHorror) {
                    System.out.printf("  🚫 恐怖/惊悚: %s\n", name);
                    continue;
                }
                if (isValidTmdbMovie(searchInfo)) {
                    currentTmdbMovieInfo = searchInfo;
                    currentFilmTag = autoMapFilmTagByGenres(searchInfo);
                    System.out.printf("  🎉 经典片选中：《%s》| 标签：【%s】\n", searchInfo.title, currentFilmTag);
                    return searchInfo.title;
                } else {
                    System.out.printf("  ❌ 校验不通过: %s (评分低/简介短)\n", name);
                }
            }

            // ========== 第三步：纯大模型兜底 ==========
            System.out.println("\n👉 【步骤三】经典片验证失败，纯大模型兜底...");
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            List<String> aiPool = aiGenerateTaggedMoviePool();
            for (String n : aiPool) {
                if (!isBlackMovie(n) && !used.contains(n)) {
                    currentTmdbMovieInfo = null;
                    System.out.printf("  🎉 兜底选中：《%s》\n", n);
                    return n;
                }
            }
            System.out.println("  ❌ 本次尝试未找到可用影片");
        }
        throw new Exception("多次选片尝试均失败，请扩充候选池或清理Gist记录");
    }

    /**
     * 从 TMDB 拉取 trending(热搜) + now_playing + popular 合并候选
     */
    private static List<TmdbMovieInfo> fetchTmdbCandidates() {
        List<Long> idList = new ArrayList<>();
        
        // 1. 优先拉取本周热搜/趋势 (Trending)
        System.out.println("  🔥 [TMDB] 拉取本周热搜/趋势电影 (trending/week)...");
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/trending/movie/week")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                    if (results != null) {
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            if (obj.getDoubleValue("vote_average") >= TMDB_MIN_VOTE) {
                                idList.add(obj.getLongValue("id"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("  ❌ trending 异常: " + e.getMessage());
        }

        // 2. now_playing 补充
        System.out.println("  🎬 [TMDB] 拉取正在热映 (now_playing)...");
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/now_playing")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                    if (results != null) {
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            long mid = obj.getLongValue("id");
                            if (obj.getDoubleValue("vote_average") >= TMDB_MIN_VOTE && !idList.contains(mid)) {
                                idList.add(mid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("  ❌ now_playing 异常: " + e.getMessage());
        }

        // 3. popular 补充
        if (idList.size() < 6) {
            System.out.println("  🌟 [TMDB] 候选不足，补充热门榜单 (popular)...");
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                        .newBuilder()
                        .addQueryParameter("api_key", TMDB_API_KEY)
                        .addQueryParameter("language", "zh-CN")
                        .build();
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                        if (results != null) {
                            for (Object o : results) {
                                JSONObject obj = (JSONObject) o;
                                long mid = obj.getLongValue("id");
                                if (obj.getDoubleValue("vote_average") >= TMDB_MIN_VOTE && !idList.contains(mid)) {
                                    idList.add(mid);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("  ❌ popular 异常: " + e.getMessage());
            }
        }

        System.out.printf("  📝 TMDB 待查详情 ID (%d个)\n", idList.size());
        
        List<TmdbMovieInfo> validList = new ArrayList<>();
        for (long mid : idList) {
            TmdbMovieInfo info = tmdbGetMovieDetail(mid);
            if (isValidTmdbMovie(info)) {
                validList.add(info);
            }
        }
        validList.sort(Comparator.comparingInt((TmdbMovieInfo m) -> m.overview.length()).reversed());
        return validList;
    }

    /**
     * 大模型从候选列表中挑选最适合公众号影评的电影 (结合热点情绪)
     */
    private static Long aiSelectBestFilm(List<TmdbMovieInfo> candidates) throws IOException {
        JSONArray jsonArr = new JSONArray();
        for (TmdbMovieInfo info : candidates) {
            JSONObject item = new JSONObject();
            item.put("id", info.id);
            item.put("title", info.title);
            item.put("vote_average", info.voteAverage);
            item.put("overview", info.overview);
            jsonArr.add(item);
        }

        // ⚠️ Prompt 升级：强制结合近期社会热点/热搜情绪
        String prompt = "你是资深公众号影视主编，深谙网络热点与大众情绪。\n" +
                "下面是一批候选电影，请结合【近期社会热点话题/热搜情绪】（如：职场内耗、亲密关系困境、生存压力、女性觉醒、原生家庭、反内卷等），从中挑选最适合写公众号深度爆款影评的一部。\n" +
                "筛选标准：\n" +
                "1. 极具话题度，能蹭上近期网络热点情绪，容易引发读者转发和共鸣，拒绝自嗨和纯爆米花爽片；\n" +
                "2. 简介信息充足，有足够的人性/现实解读空间；\n" +
                "3. 候选列表已过滤恐怖惊悚题材；\n" +
                "4. 只输出JSON，格式 {\"selectedId\":数字}。\n" +
                "如果全部都不具备热点话题潜力，输出 {\"selectedId\":null}。\n" +
                "候选列表：\n" + jsonArr;

        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp).trim();
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start != -1 && end > start) resp = resp.substring(start, end + 1);

        try {
            JSONObject jo = JSON.parseObject(resp);
            Object sid = jo.get("selectedId");
            if (sid == null) return null;
            return jo.getLong("selectedId");
        } catch (Exception e) {
            System.err.println("  ⚠️ AI选片解析失败: " + e.getMessage());
            return null;
        }
    }

    private static String autoMapFilmTagByGenres(TmdbMovieInfo info) {
        if (info == null || info.genres == null || info.genres.isEmpty()) {
            return FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
        }
        List<Integer> genreIds = new ArrayList<>();
        info.genres.forEach(g -> genreIds.add(g.id));

        if (genreIds.contains(18) && genreIds.contains(80)) return "社会讽刺、现实隐喻";
        if (genreIds.contains(18) || genreIds.contains(9648)) return "现实扎心、人间百态";
        if (genreIds.contains(80)) return "人性深度、善恶博弈";
        if (genreIds.contains(10749)) return "青春成长、遗憾治愈";
        if (genreIds.contains(10751)) return "亲情羁绊、烟火人间";
        if (genreIds.contains(35)) return "温情治愈、治愈内耗";

        List<String> fallbackTags = List.of(
                "人性深度、自我救赎", "底层生活、人间真实", "平凡人性、微光治愈",
                "成长取舍、直面人生", "人生百态、世事通透", "岁月沉淀、人间清醒", "市井烟火、平凡众生");
        return fallbackTags.get(ThreadLocalRandom.current().nextInt(fallbackTags.size()));
    }

    private static List<String> aiGetClassicMovieNamesByTag(String tag) throws IOException {
        // ⚠️ Prompt 升级：要求推荐能切中当下痛点、自带热搜属性的经典片
        String prompt = "你是资深公众号影视主编，深谙网络热点与大众情绪。\n" +
                "请结合【近期容易引发全网共鸣的社会热点/热搜情绪】（如：反内卷、搞钱焦虑、中年危机、女性独立、原生家庭创伤等），并根据风格标签【" + tag + "】，输出最多5部世界范围内真实公映过的高分经典电影中文片名。\n" +
                "硬性约束：\n" +
                "1. 必须是能切中当下社会痛点、自带热搜话题属性的电影；\n" +
                "2. 严禁编造虚构影片，片名准确；过滤恐怖、惊悚、鬼怪；\n" +
                "3. 只输出纯净JSON字符串数组，不要任何解释。\n" +
                "输出示例：[\"怦然心动\",\"肖申克的救赎\"]";
        
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("  ⚠️ 经典影片获取失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部真实上映的高分电影中文片名。\n" +
                "硬性约束：\n" +
                "1.禁止编造不存在影片；过滤恐怖、惊悚、鬼怪题材。\n" +
                "2.贴合标签调性，适合公众号深度影评。\n" +
                "3.只输出纯净JSON数组。\n" +
                "输出：[\"电影1\",\"电影2\"]";
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("  ⚠️ 兜底片库生成失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== TMDB 接口 ====================
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
                JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                if (results == null || results.isEmpty()) return null;
                long mid = results.getJSONObject(0).getLongValue("id");
                return tmdbGetMovieDetail(mid);
            }
        } catch (Exception e) {
            System.err.println("  ❌ 影片搜索异常:" + e.getMessage());
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
            System.err.println("  ❌ 影片详情异常:" + e.getMessage());
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

    // ==================== 影评生成 ====================
    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        int emptyCount = 0;
        String safeOverview = (tmdbOverview == null || tmdbOverview.isBlank()) ? "" : tmdbOverview.replace("“", "\"").replace("”", "\"");
        boolean weakOverview = safeOverview.length() < OVERVIEW_WEAK_THRESHOLD;
        System.out.printf("\n✍️ [生成器] 简介长度：%d，薄弱=%b\n", safeOverview.length(), weakOverview);

        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("\n🔄 [影评生成] 第 %d/%d 轮\n", i + 1, ARTICLE_MAX_RETRY);
            String prompt;
            int currentMaxToken;
            double currentTemp;
            
            if (weakOverview) {
                prompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_EXPAND;
                currentTemp = TEMPERATURE_EXPAND;
            } else {
                prompt = i < 2
                        ? String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag)
                        : String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_NORMAL;
                currentTemp = TEMPERATURE_NORMAL;
            }

            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt, currentMaxToken, currentTemp);
            } catch (IOException ex) {
                System.err.printf("  ❌ 网络异常: %s\n", ex.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }

            contentRaw = stripCodeBlock(contentRaw).trim();
            int start = contentRaw.indexOf('{');
            int end = contentRaw.lastIndexOf('}');
            if (start != -1 && end > start) contentRaw = contentRaw.substring(start, end + 1);

            if (contentRaw.isBlank()) {
                emptyCount++;
                if (emptyCount >= 3) throw new MovieCannotHandleException("连续空返回");
                sleepRandom(1200, 2500);
                continue;
            }

            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.err.printf("  ❌ JSON解析失败: %s\n", e.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }

            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");

            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                System.err.printf("  ❌ JSON字段缺失\n");
                sleepRandom(1200, 2500);
                continue;
            }

            article = cleanAiArticle(article);
            article = removeInteractionCTA(article);

            int len = article.length();
            System.out.printf("  📏 稿件长度: %d | 目标: [%d~%d]\n", len, ARTICLE_TARGET_MIN, ARTICLE_TARGET_MAX);
            if (len >= ARTICLE_TARGET_MIN && len <= ARTICLE_TARGET_MAX) {
                ReviewResult result = new ReviewResult();
                result.centralArgument = centralArg;
                result.titles = titleArr.toList(String.class);
                result.article = article;
                System.out.println("  ✅ 字数达标，生成成功！");
                return result;
            }
            sleepRandom(1200, 2500);
        }

        System.out.println("\n🚨 [保底] 常规重试耗尽，强制扩写...");
        String finalPrompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
        String finalRaw = callDeepSeek(finalPrompt, MAX_TOKENS_EXPAND, TEMPERATURE_EXPAND);
        finalRaw = stripCodeBlock(finalRaw).trim();
        
        int fStart = finalRaw.indexOf('{');
        int fEnd = finalRaw.lastIndexOf('}');
        if (fStart != -1 && fEnd > fStart) finalRaw = finalRaw.substring(fStart, fEnd + 1);
        
        JSONObject fjo = JSON.parseObject(finalRaw);
        ReviewResult finalRes = new ReviewResult();
        finalRes.centralArgument = fjo.getString("centralArgument");
        finalRes.titles = fjo.getJSONArray("titles").toList(String.class);
        finalRes.article = cleanAiArticle(fjo.getString("article"));
        finalRes.article = removeInteractionCTA(finalRes.article);

        if (finalRes.article.length() < ARTICLE_TARGET_MIN) {
            StringBuilder sb = new StringBuilder(finalRes.article);
            while (sb.length() < ARTICLE_TARGET_MIN) {
                sb.append("\n\n很多时候，电影里看见的是别人的故事，映照的却是我们自己一路走来的人生境遇。那些遗憾、挣扎与和解，不止发生在银幕之上，也藏在每一个普通人日复一日的生活之中。");
            }
            finalRes.article = sb.toString();
        }
        if (finalRes.article.length() > ARTICLE_TARGET_MAX) {
            finalRes.article = finalRes.article.substring(0, ARTICLE_TARGET_MAX);
        }
        return finalRes;
    }

    // ==================== 飞书卡片 ====================
    private static void sendFeishuCard(String movieName, ReviewResult result, int articleLength) {
        try {
            JSONObject card = new JSONObject();
            card.put("msg_type", "interactive");
            JSONObject cardContent = new JSONObject();

            JSONObject header = new JSONObject();
            JSONObject title = new JSONObject();
            title.put("tag", "plain_text");
            title.put("content", "🎬 公众号影评 | 《" + movieName + "》");
            header.put("title", title);
            header.put("template", "blue");
            cardContent.put("header", header);

            JSONArray elements = new JSONArray();

            JSONObject infoDiv = new JSONObject();
            infoDiv.put("tag", "div");
            JSONObject infoText = new JSONObject();
            infoText.put("tag", "lark_md");
            infoText.put("content",
                    "**🏷️ 风格标签：**" + currentFilmTag + "\n" +
                    "**💡 中心论点：**" + result.centralArgument + "\n" +
                    "**📏 正文长度：**" + articleLength + " 字符");
            infoDiv.put("text", infoText);
            elements.add(infoDiv);

            elements.add(buildDivider());

            JSONObject titleDiv = new JSONObject();
            titleDiv.put("tag", "div");
            JSONObject titleText = new JSONObject();
            titleText.put("tag", "lark_md");
            StringBuilder titleSb = new StringBuilder("**📌 候选标题：**\n");
            for (int i = 0; i < result.titles.size(); i++) {
                titleSb.append(i + 1).append(". ").append(result.titles.get(i)).append("\n");
            }
            titleText.put("content", titleSb.toString().trim());
            titleDiv.put("text", titleText);
            elements.add(titleDiv);

            elements.add(buildDivider());

            JSONObject articleDiv = new JSONObject();
            articleDiv.put("tag", "div");
            JSONObject articleText = new JSONObject();
            articleText.put("tag", "lark_md");
            articleText.put("content", result.article);
            articleDiv.put("text", articleText);
            elements.add(articleDiv);

            cardContent.put("elements", elements);
            card.put("card", cardContent);

            RequestBody rb = RequestBody.create(card.toJSONString(), MediaType.get("application/json; charset=utf-8"));
            Request req = new Request.Builder()
                    .url(FEISHU_WEBHOOK)
                    .post(rb)
                    .build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    System.out.println("✅ [飞书] 卡片发送成功");
                } else {
                    System.err.println("❌ [飞书] 发送失败, Code: " + resp.code() + ", Body: " + resp.body().string());
                }
            }
        } catch (Exception e) {
            System.err.println("❌ [飞书] 发送异常: " + e.getMessage());
        }
    }

    private static JSONObject buildDivider() {
        JSONObject divider = new JSONObject();
        divider.put("tag", "hr");
        return divider;
    }

    // ==================== Gist 读写 ====================
    private static List<String> loadUsedFromGist() {
        // ⚠️ 修复 URL 格式
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("⚠️ [Gist] 读取失败, Code: " + resp.code());
                return new ArrayList<>();
            }
            JSONObject gistJson = JSON.parseObject(resp.body().string());
            JSONObject files = gistJson.getJSONObject("files");
            if (files == null || !files.containsKey(GIST_FILENAME)) {
                return new ArrayList<>();
            }
            String content = files.getJSONObject(GIST_FILENAME).getString("content");
            if (content == null || content.isBlank()) return new ArrayList<>();
            return JSON.parseArray(content).toList(String.class);
        } catch (Exception e) {
            System.err.println("⚠️ [Gist] 读取异常: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void saveUsedToGist(List<String> usedMovies) throws IOException {
        // ⚠️ 修复 URL 格式
        String url = "https://api.github.com/gists/" + GIST_ID;
        JSONObject fileObj = new JSONObject();
        fileObj.put("content", new JSONArray(usedMovies).toJSONString());
        JSONObject filesObj = new JSONObject();
        filesObj.put(GIST_FILENAME, fileObj);
        JSONObject bodyObj = new JSONObject();
        bodyObj.put("files", filesObj);

        RequestBody rb = RequestBody.create(bodyObj.toJSONString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .patch(rb)
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                System.out.println("✅ [Gist] 保存成功，共 " + usedMovies.size() + " 部");
            } else {
                System.err.println("❌ [Gist] 保存失败, Code: " + resp.code());
            }
        }
    }

    // ==================== DeepSeek 调用 ====================
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
                    throw new IOException("DeepSeek请求失败：" + resp.code());
                }
                JSONObject jo = JSON.parseObject(raw);
                JSONObject choice0 = jo.getJSONArray("choices").getJSONObject(0);
                JSONObject msgObj = choice0.getJSONObject("message");
                String modelContent = msgObj.getString("content");
                String reasoningContent = msgObj.getString("reasoning_content");

                if ((modelContent == null || modelContent.isBlank()) && reasoningContent != null && !reasoningContent.isBlank()) {
                    String temp = stripCodeBlock(reasoningContent).trim();
                    int tStart = temp.indexOf('{');
                    int tEnd = temp.lastIndexOf('}');
                    if (tStart != -1 && tEnd > tStart) {
                        temp = temp.substring(tStart, tEnd + 1);
                        JSON.parseObject(temp);
                        modelContent = temp;
                    }
                }
                if (modelContent == null || modelContent.isBlank()) return "";
                return modelContent.trim();
            } catch (IOException e) {
                lastEx = e;
                System.err.printf("  ❌ [DeepSeek] retry=%d err=%s\n", r, e.getMessage());
            }
        }
        throw new IOException("DeepSeek重试耗尽", lastEx);
    }

    // ==================== 工具方法 ====================
    private static String removeInteractionCTA(String article) {
        if (article == null || article.isBlank()) return article;
        String[] ctaPhrases = {
                "评论区聊聊", "评论区说说", "评论区见", "评论区等你", "评论区聊起来",
                "评论区留言", "评论区告诉我", "欢迎留言", "欢迎在评论区", "欢迎讨论",
                "欢迎分享", "留言告诉我", "留言说说", "留言区见", "留言区等你",
                "说说你的看法", "分享你的看法", "分享你的故事",
                "你怎么看？欢迎讨论", "你怎么看，欢迎讨论",
                "你觉得呢？欢迎留言", "你觉得呢，欢迎留言"
        };
        String result = article;
        for (String phrase : ctaPhrases) {
            result = result.replace(phrase, "");
        }
        result = result.replaceAll("\\n{3,}", "\n\n");
        return result.trim();
    }

    private static String cleanAiArticle(String text) {
        if (text == null) return "";
        text = text.replaceAll("【.*?】", "");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static void sleepRandom(int minMs, int maxMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripCodeBlock(String text) {
        String s = text.trim();
        Pattern codePattern = Pattern.compile("^[a-zA-Z0-9]*\\R(.*?)\\R$", Pattern.DOTALL);
        Matcher matcher = codePattern.matcher(s);
        if (matcher.matches()) {
            s = matcher.group(1).trim();
        }
        return s.trim();
    }
}
