package com.wang.springboottemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
    private static final int FEISHU_CARD_SAFE_MAX = 2500;
    private static final int MAX_TOKENS = 3200;
    private static final double TEMPERATURE = 0.42;
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

    // ==========融合两套Skill的主Prompt模板 ==========
    private static final String MAIN_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人，融合公众号爆款写作台与影评写作助手两套规范。面向普通公众号读者，拒绝晦涩学院派话术。\n" +
            "写作底层逻辑：电影只是载体，输出人性、现实痛点、情绪共鸣，提升文章收藏、转发、评论数据，拒绝纯剧情流水账复述。\n" +
            "\n" +
            "🔴【最高优先级·防幻觉事实约束】\n" +
            "严禁编造剧情、人物、台词、名场面、细节伏笔。所有引用的情节、对话、人物行为必须是影片客观真实内容；不知道、拿不准的细节直接舍弃，禁止脑补杜撰。\n" +
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
            "Step7 生成封面提示词（2.35:1公众号宽幅封面，电影氛围感，无文字），生成3处正文配图提示词。\n" +
            "Step8 质量门禁自查报告，逐项核验：中心论点、事实有无编造、反问句数量、剧透控制、公众号适配、AI痕迹、合规情况。\n" +
            "\n" +
            "🚫公众号合规铁律：正文不要放链接、微信号；不要出现“点赞转发收藏”指令。\n" +
            "\n" +
            "✅【输出JSON强制格式，只输出JSON，禁止```、禁止注释、禁止额外说明】\n" +
            "{\n" +
            "  \"centralArgument\":\"一句话中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"完整公众号markdown正文，保留加粗语法\",\n" +
            "  \"coverPrompt\":\"公众号封面AI绘图提示词，2.35:1宽幅\",\n" +
            "  \"imagePrompts\":[\"配图1提示词\",\"配图2提示词\",\"配图3提示词\"],\n" +
            "  \"selfCheckReport\":\"自查报告，逐条列出核验结果\"\n" +
            "}\n" +
            "\n" +
            "为电影《%s》撰写公众号影评，影片风格标签【%s】。\n" +
            "正文总汉字1800‑2500；不清楚的影片细节绝不编造，只返回JSON。";

    // 兜底降级Prompt，重试后期，防幻觉、去AI味
    private static final String FALLBACK_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守】\n" +
            "角色：公众号影评撰稿人，遵循影评写作助手+公众号爆款写作台规范。\n" +
            "🔴最高约束：严禁编造剧情、台词、人物细节；不确定的内容直接省略，禁止脑补；区分事实与主观观点。\n" +
            "写作逻辑：少复述剧情，多输出人性感悟现实共鸣；全文至少2个反问；结尾问句互动。\n" +
            "\n" +
            "写作规范：\n" +
            "1.输出一句中心论点；输出3条公众号钩子标题，禁止观后感、浅析类标题。\n" +
            "2.开篇简短抓情绪；剧情简介最大150字，只写真实关键片段。\n" +
            "3.主体3‑4个解读角度，全部基于影片真实细节，落地普通人生活感受。\n" +
            "4.结尾金句+问句形式评论区引导；段落短小适配手机；去除AI模板化套话。\n" +
            "5.正文1600‑2200汉字。\n" +
            "6.输出封面提示词、3个配图提示词、质量自查报告。\n" +
            "\n" +
            "✅输出JSON格式，禁止代码块、多余文字：\n" +
            "{\n" +
            "  \"centralArgument\":\"中心论点\",\n" +
            "  \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            "  \"article\":\"正文markdown\",\n" +
            "  \"coverPrompt\":\"封面提示词\",\n" +
            "  \"imagePrompts\":[\"图1\",\"图2\",\"图3\"],\n" +
            "  \"selfCheckReport\":\"自查报告\"\n" +
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

    public static class ReviewResult {
        public String centralArgument;
        public List<String> titles;
        public String article;
        public String coverPrompt;
        public List<String> imagePrompts;
        public String selfCheckReport;
    }

    // 自定义异常：影片模型无法处理，需要重新选片
    public static class MovieCannotHandleException extends Exception {
        public MovieCannotHandleException(String msg) {
            super(msg);
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("===== 影评生成任务启动 =====");
            checkEnv();
            List<String> usedMovies = loadUsedFromGist();
            System.out.println("已处理电影数量：" + usedMovies.size());
            ReviewResult reviewResult = null;
            String pickedMovie = null;
            // 在main层循环选片，遇到无法处理影片就重新选
            for (int attempt = 0; attempt < PICK_MAX_RETRY; attempt++) {
                pickedMovie = pickOneMovie(usedMovies);
                System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);
                try {
                    reviewResult = generateReview(pickedMovie);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("[WARN] 当前影片[%s]模型无法处理，重新选片，msg=%s%n", pickedMovie, e.getMessage());
                    // 加入已使用，避免重复选中这部坏片
                    usedMovies.add(pickedMovie);
                }
            }
            if (reviewResult == null) {
                throw new Exception("多次选片仍然无法产出影评");
            }
            System.out.println("中心论点：" + reviewResult.centralArgument);
            System.out.println("候选标题：" + reviewResult.titles);
            System.out.println("影评正文长度：" + reviewResult.article.length());
            sendFeishuCard(pickedMovie, reviewResult);
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

    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            List<String> candidates;
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                currentFilmTag = FILM_TAGS[(int) (Math.random() * FILM_TAGS.length)];
                candidates = fetchTmdbMovies();
                System.out.println("[LOG] 使用TMDB接口选片，当前标签：" + currentFilmTag);
            } else {
                System.out.println("[LOG] TMDB_API_KEY为空，使用AI生成电影池");
                candidates = aiGenerateTaggedMoviePool();
            }
            if (candidates == null || candidates.isEmpty()) {
                System.out.println("本次候选池为空，重新生成风格化电影池");
                candidates = aiGenerateTaggedMoviePool();
            }
            // 过滤黑名单影片
            List<String> safeCandidates = new ArrayList<>();
            for (String name : candidates) {
                boolean black = false;
                for (String kw : MOVIE_BLACKLIST_KEYWORD) {
                    if (name.contains(kw)) {
                        black = true;
                        break;
                    }
                }
                if (!black && !used.contains(name)) {
                    safeCandidates.add(name);
                }
            }
            if (!safeCandidates.isEmpty()) {
                return safeCandidates.get(0);
            }
            System.out.println("本轮候选全部已使用或者命中黑名单，重新获取电影池");
        }
        throw new Exception("多次尝试找不到未使用安全电影，请扩充候选池或清理Gist记录");
    }

    private static List<String> fetchTmdbMovies() {
        List<String> list = new ArrayList<>();
        try {
            HttpUrl nowPlayingUrl = HttpUrl.parse(TMDB_BASE + "/movie/now_playing")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request reqNow = new Request.Builder().url(nowPlayingUrl).get().build();
            try (Response resp = HTTP_CLIENT.newCall(reqNow).execute()) {
                System.out.println("[LOG] TMDB now_playing response code=" + resp.code());
                if (resp.isSuccessful()) {
                    JSONObject json = JSON.parseObject(resp.body().string());
                    JSONArray results = json.getJSONArray("results");
                    if (results != null && !results.isEmpty()) {
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            double vote = obj.getDoubleValue("vote_average");
                            String title = obj.getString("title");
                            // 过滤：片名必须包含中文汉字，过滤纯英文片名
                            if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank() && hasChineseChar(title)) {
                                list.add(title);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("拉取热映影片异常：" + e.getMessage());
        }
        if (list.size() < 5) {
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                        .newBuilder()
                        .addQueryParameter("api_key", TMDB_API_KEY)
                        .addQueryParameter("language", "zh-CN")
                        .build();
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    System.out.println("[LOG] TMDB popular response code=" + resp.code());
                    if (resp.isSuccessful()) {
                        String bodyStr = resp.body().string();
                        JSONObject json = JSON.parseObject(bodyStr);
                        JSONArray results = json.getJSONArray("results");
                        if (results != null && !results.isEmpty()) {
                            for (Object o : results) {
                                JSONObject obj = (JSONObject) o;
                                double vote = obj.getDoubleValue("vote_average");
                                String title = obj.getString("title");
                                if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank()
                                        && hasChineseChar(title) && !list.contains(title)) {
                                    list.add(title);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("TMDB popular请求异常：" + e.getMessage());
            }
        }
        if (list.isEmpty()) {
            System.out.println("TMDB获取影片为空，降级AI风格选片");
            try {
                return aiGenerateTaggedMoviePool();
            } catch (IOException ex) {
                return new ArrayList<>();
            }
        }
        return list;
    }

    // 判断字符串是否包含中文汉字，过滤纯英文片名
    private static boolean hasChineseChar(String s) {
        for (char c : s.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        int tagIndex = (int) (Math.random() * FILM_TAGS.length);
        currentFilmTag = FILM_TAGS[tagIndex];
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部**真实上映**的高分电影中文片名。\n" +
                "硬性约束：\n" +
                "1.禁止编造不存在影片，片名必须准确；过滤恐怖、惊悚、鬼怪题材。\n" +
                "2.贴合标签调性，适合公众号深度影评，大众认知度高。\n" +
                "3.只输出纯净JSON数组，不要任何解释、序号。\n" +
                "输出：[\"电影1\",\"电影2\"]";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static ReviewResult generateReview(String movieName) throws Exception {
        ReviewResult fallbackResult = null;
        int emptyCount = 0;
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("[LOG] 影评生成第%d轮重试%n", i + 1);
            String prompt;
            if (i < 2) {
                prompt = String.format(MAIN_REVIEW_PROMPT_TPL, movieName, currentFilmTag);
            } else {
                prompt = String.format(FALLBACK_REVIEW_PROMPT_TPL, movieName, currentFilmTag);
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
            String coverPro = jo.getString("coverPrompt");
            JSONArray imgArr = jo.getJSONArray("imagePrompts");
            String checkReport = jo.getString("selfCheckReport");
            if (article == null || titleArr == null || titleArr.size() != 3
                    || centralArg == null || coverPro == null || imgArr == null || checkReport == null) {
                System.out.println("[WARN] JSON字段缺失，重试生成");
                sleepRandom(1200, 2500);
                continue;
            }
            ReviewResult temp = new ReviewResult();
            temp.centralArgument = centralArg;
            temp.titles = titleArr.toList(String.class);
            temp.article = article;
            temp.coverPrompt = coverPro;
            temp.imagePrompts = imgArr.toList(String.class);
            temp.selfCheckReport = checkReport;
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
        if (s.startsWith("```")) {
            int firstNewLine = s.indexOf('\n');
            int lastBackTick = s.lastIndexOf("```");
            if (firstNewLine > 0 && lastBackTick > firstNewLine) {
                s = s.substring(firstNewLine + 1, lastBackTick);
            }
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
                String modelContent = jo.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
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

    private static void sendFeishuCard(String movie, ReviewResult reviewResult) throws IOException {
        StringBuilder mdSb = new StringBuilder();
        mdSb.append("**🎬《").append(movie).append("》公众号影评产出**\n");
        mdSb.append("**影片风格：").append(currentFilmTag).append("**\n\n");
        mdSb.append("**💡中心论点：**").append(reviewResult.centralArgument).append("\n\n");
        mdSb.append("**📝备选标题：**\n");
        for (String t : reviewResult.titles) {
            mdSb.append("- ").append(t).append("\n");
        }
        mdSb.append("\n**🖼️封面提示词：**\n").append(reviewResult.coverPrompt).append("\n\n");
        mdSb.append("**📷配图提示词：**\n");
        for (int i = 0; i < reviewResult.imagePrompts.size(); i++) {
            mdSb.append(i + 1).append(". ").append(reviewResult.imagePrompts.get(i)).append("\n");
        }
        mdSb.append("\n**✅质量自查报告：**\n").append(reviewResult.selfCheckReport).append("\n\n");
        mdSb.append("**📄完整影评正文**\n");
        mdSb.append(reviewResult.article);
        String mdContent = mdSb.toString();
        if (mdContent.length() > FEISHU_CARD_SAFE_MAX) {
            int pos = FEISHU_CARD_SAFE_MAX - 150;
            String temp = mdContent.substring(0, pos);
            int lastLineBreak = temp.lastIndexOf('\n');
            if (lastLineBreak > 800) {
                mdContent = mdContent.substring(0, lastLineBreak);
            } else {
                mdContent = temp;
            }
            mdContent += "\n\n……（内容已截断，注意务必人工核验影片事实后再发布）";
        }
        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONObject ele = new JSONObject();
        ele.put("tag", "div");
        ele.put("text", JSONObject.of("tag", "lark_md", "content", mdContent));
        JSONArray elements = new JSONArray();
        elements.add(ele);
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
}
