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

    // 最终定稿区间：1800-2500（飞书单卡完整展示）
    private static final int ARTICLE_IDEAL_MIN = 1800;
    private static final int ARTICLE_IDEAL_MAX = 2500;
    // 兜底区间：1500 ~ 2500，超长直接丢弃
    private static final int ARTICLE_SOFT_MIN = 1500;
    // 飞书单张卡片安全上限
    private static final int FEISHU_CARD_SAFE_MAX = 2500;

    // ==========【改动】调小max_tokens，匹配目标输出，防止模型疯狂写超长 ==========
    private static final int MAX_TOKENS = 3400;
    private static final double TEMPERATURE = 0.45;
    // callDeepSeek内部：仅网络异常重试次数；业务异常(空内容、json错误)不在这里重试
    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    private static final String[] FILM_TAGS = {
            "现实扎心、人间百态",
            "人性深度、自我救赎",
            "青春成长、遗憾治愈",
            "社会讽刺、现实隐喻",
            "温情治愈、治愈内耗"
    };

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static String currentFilmTag = "";

    public static class ReviewResult {
        public List<String> titles;
        public String article;
    }

    public static void main(String[] args) {
        try {
            System.out.println("===== 影评生成任务启动 =====");
            checkEnv();
            List<String> usedMovies = loadUsedFromGist();
            System.out.println("已处理电影数量：" + usedMovies.size());
            String pickedMovie = pickOneMovie(usedMovies);
            System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);

            ReviewResult reviewResult = generateReview(pickedMovie);
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
                candidates = fetchTmdbMovies();
                currentFilmTag = FILM_TAGS[(int) (Math.random() * FILM_TAGS.length)];
                System.out.println("[LOG] 使用TMDB接口选片，当前标签：" + currentFilmTag);
            } else {
                System.out.println("[LOG] TMDB_API_KEY为空，使用AI生成电影池");
                candidates = aiGenerateTaggedMoviePool();
            }
            if (candidates == null || candidates.isEmpty()) {
                System.out.println("本次候选池为空，重新生成风格化电影池");
                candidates = aiGenerateTaggedMoviePool();
            }
            for (String name : candidates) {
                if (!used.contains(name)) {
                    return name;
                }
            }
            System.out.println("本轮候选全部已使用，重新获取电影池");
        }
        throw new Exception("多次尝试找不到未使用电影，请扩充候选池或清理Gist记录");
    }

    private static List<String> fetchTmdbMovies() {
        List<String> list = new ArrayList<>();
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                    .newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY)
                    .addQueryParameter("language", "zh-CN")
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                System.out.println("[LOG] TMDB response code=" + resp.code());
                if (!resp.isSuccessful()) {
                    System.out.printf("TMDB接口调用失败 code=%d，降级AI风格选片%n", resp.code());
                    return aiGenerateTaggedMoviePool();
                }
                String bodyStr = resp.body().string();
                JSONObject json = JSON.parseObject(bodyStr);
                JSONArray results = json.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    System.out.println("TMDB返回影片列表为空，降级AI风格选片");
                    return aiGenerateTaggedMoviePool();
                }
                for (Object o : results) {
                    JSONObject obj = (JSONObject) o;
                    double vote = obj.getDoubleValue("vote_average");
                    String title = obj.getString("title");
                    if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank()) {
                        list.add(title);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("TMDB请求异常：" + e.getMessage() + "，降级AI风格选片");
            try {
                return aiGenerateTaggedMoviePool();
            } catch (IOException ex) {
                return new ArrayList<>();
            }
        }
        return list.isEmpty() ? new ArrayList<>() : list;
    }

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        int tagIndex = (int) (Math.random() * FILM_TAGS.length);
        currentFilmTag = FILM_TAGS[tagIndex];
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部国内外高分经典电影中文名称。\n" +
                "要求：\n" +
                "1. 严格贴合标签风格，题材统一、调性一致；\n" +
                "2. 避开烂片、要是有质量的影片，适合深度解读、自带流量的爆款潜质影片；\n" +
                "3. 只输出纯净JSON数组，不要解释、不要序号、不要多余文字。";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static ReviewResult generateReview(String movieName) throws Exception {
        ReviewResult fallbackResult = null;
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("[LOG] 影评生成第%d轮重试%n", i + 1);
            // ==========【改动】重写Prompt，把硬性约束放最前面，强化JSON+字数指令 ==========
              String prompt = "【硬性规则，必须严格遵守，违反直接作废】\n" +
            "角色设定：\n" +
            "你是资深公众号爆款影评撰稿人，兼具专业影评人的解读深度，目标读者是普通公众号大众，不是深度影迷。" +
            "拒绝学院派晦涩术语，不走纯剧情复述，遵循公众号10W+影评逻辑：电影只是载体，内核输出人性、现实、情绪共鸣，提升转发、收藏、评论。\n" +
            "写作规则严格遵守：\n" +
            "1.选题视角：优先情绪痛点、人物人性、现实映射、伏笔细节；可做反差解读，观点保持客观克制，不极端吹捧、不恶意踩片。\n" +
            "2.标题：输出3‑5个公众号风格标题，包含共鸣式、反差式、提问式，禁止“XX观后感”“浅析XX”这类平淡标题。\n" +
            "3.开篇：3秒抓读者，使用情绪/悬念/场景代入，不要大段介绍导演、幕后花絮，100字以内。\n" +
            "4.正文结构使用四段式：\n" +
            "1.开篇入题\n" +
            "2.精简剧情铺垫（200字以内，只写支撑观点的关键情节、名场面，禁止流水账完整复述整部电影）\n" +
            "3.主体解读（全文60%篇幅，分3‑4个角度，每个观点必须绑定电影细节/台词，最后落地普通人现实生活感悟，不要悬浮空谈）\n" +
            "4.结尾升华，输出可摘抄金句，带上评论区互动引导。\n" +
            "【行文要求：段落简短，适合手机阅读；关键金句加粗；少讲镜头语言、剪辑技术，大众不关心这些；感悟要有依据，拒绝空洞鸡汤。】\n" +
            "1、输出必须是完整闭合JSON对象，**禁止任何```代码块，禁止前置/后置说明文字，禁止注释**；\n" +
            "2、影评正文字符严格控制：1800‑2500汉字。接近2500必须立刻收尾，禁止超长；不足1800就丰富思辨分析，禁止靠复述剧情凑字数；\n" +
            "3、JSON结构固定：{\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"完整影评正文\"}\n" +
            "4、titles必须恰好3条公众号爆款标题，带情绪钩子；article为纯文本，不要markdown。\n\n" +
            "你是资深专业影评人，为电影《" + movieName + "》创作影评，影片风格标签【" + currentFilmTag + "】。\n" +
            "影评写作要求：\n" +
            "- 重镜头隐喻、人物困境、人性思辨；剧情简述，拒绝大段复述故事；\n" +
            "- 开篇尽量能够吸引人，拆解内核，延伸现实共情，结尾金句收束；段落简短适合手机阅读。\n" +
            "- 只输出JSON，不要输出JSON之外任何内容。";

            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt);
            } catch (IOException ex) {
                System.err.println("[ERROR] callDeepSeek IO异常：" + ex.getMessage());
                sleepRandom(1200,2500);
                continue;
            }
            contentRaw = stripCodeBlock(contentRaw).trim();
            System.out.println("AI返回原始JSON片段：" + contentRaw.substring(0, Math.min(220, contentRaw.length())));

            if (contentRaw.isBlank()) {
                System.out.println("[WARN] AI返回为空，休眠后重试");
                sleepRandom(1200,2500);
                continue;
            }
            // ==========【改动】简单预校验：首尾大括号，快速过滤截断输出 ==========
            if(!contentRaw.startsWith("{") || !contentRaw.endsWith("}")){
                System.out.println("[WARN] JSON首尾括号不完整，截断输出，丢弃本轮");
                sleepRandom(1200,2500);
                continue;
            }

            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.out.printf("JSON解析失败，重试，err=%s%n", e.getMessage());
                sleepRandom(1200,2500);
                continue;
            }
            if (jo == null) {
                System.out.println("parseObject返回null，重试");
                sleepRandom(1200,2500);
                continue;
            }

            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            if (article == null || titleArr == null || titleArr.size() != 3) {
                System.out.println("[WARN]字段缺失，重试生成");
                sleepRandom(1200,2500);
                continue;
            }

            ReviewResult temp = new ReviewResult();
            temp.titles = titleArr.toList(String.class);
            temp.article = article;

            int len = article.length();
            System.out.printf("[LOG]本轮稿件长度：%d，理想区间[%d,%d]，兜底下限%d%n", len, ARTICLE_IDEAL_MIN, ARTICLE_IDEAL_MAX, ARTICLE_SOFT_MIN);

            // 完美区间：直接返回
            if (len >= ARTICLE_IDEAL_MIN && len <= ARTICLE_IDEAL_MAX) {
                System.out.println("[LOG]拿到理想长度稿件，直接返回");
                return temp;
            }
            // 超长：直接丢弃，不兜底
            if (len > ARTICLE_IDEAL_MAX) {
                System.out.printf("[WARN]稿件超长(超过2500)，直接丢弃，当前长度：%d%n", len);
                sleepRandom(1200,2500);
                continue;
            }
            // 短于理想、但达标兜底：保存兜底
            if (len >= ARTICLE_SOFT_MIN) {
                System.out.printf("未达到理想下限%d，当前长度：%d，作为兜底候选保存%n", ARTICLE_IDEAL_MIN, len);
                fallbackResult = temp;
            } else {
                System.out.printf("稿件过短直接丢弃，当前长度：%d%n", len);
            }
            sleepRandom(1200,2500);
        }
        if (fallbackResult != null) {
            System.out.println("多次未拿到理想长度稿件，使用兜底稿件继续执行任务");
            return fallbackResult;
        }
        throw new Exception("多次生成无法得到符合长度的影评");
    }

    /** 随机休眠 ms */
    private static void sleepRandom(int minMs,int maxMs){
        try {
            int ms = ThreadLocalRandom.current().nextInt(minMs,maxMs+1);
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
            if (lastBackTick > firstNewLine) {
                s = s.substring(firstNewLine + 1, lastBackTick);
            }
        }
        return s.trim();
    }

    private static String callDeepSeek(String prompt) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", DEEPSEEK_MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", TEMPERATURE);
        // ==========【改动】DeepSeek开启强制JSON输出模式 ==========
        body.put("response_format", JSONObject.of("type","json_object"));

        JSONArray msgs = new JSONArray();
        msgs.add(JSONObject.of("role", "user", "content", prompt));
        body.put("messages", msgs);

        RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(DEEPSEEK_URL)
                .addHeader("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                .post(rb)
                .build();

        IOException lastEx = null;
        // ==========【改动】仅网络异常重试；业务异常（空content）不再在这里重试 ==========
        for (int r = 0; r <= DEEPSEEK_NET_RETRY; r++) {
            System.out.printf("[LOG] DeepSeek接口调用，第%d次请求%n", r + 1);
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                System.out.println("[LOG] DeepSeek http status=" + resp.code());
                String raw = resp.body().string();
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

    private static List<String> loadUsedFromGist() throws IOException {
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            System.out.println("[LOG] Gist读取接口 status=" + resp.code());
            if (!resp.isSuccessful()) {
                throw new IOException("读取Gist失败 " + resp.code());
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
        String titleBlock = "**备选标题：**\n" + String.join("\n", reviewResult.titles);
        StringBuilder mdSb = new StringBuilder();
        mdSb.append("**🎬《").append(movie).append("》影评产出**\n");
        mdSb.append("**影片风格：").append(currentFilmTag).append("**\n\n");
        mdSb.append(titleBlock).append("\n\n");
        mdSb.append("**完整影评正文**\n");
        mdSb.append(reviewResult.article);

        String mdContent = mdSb.toString();
        // 稿件本身≤2500，卡片无需截断，完整展示
        if (mdContent.length() > FEISHU_CARD_SAFE_MAX) {
            int pos = FEISHU_CARD_SAFE_MAX - 120;
            String temp = mdContent.substring(0, pos);
            int lastLineBreak = temp.lastIndexOf('\n');
            if (lastLineBreak > 1000) {
                mdContent = mdContent.substring(0, lastLineBreak);
            } else {
                mdContent = temp;
            }
            mdContent += "\n\n……（内容已精简，完整稿件看AI原始输出）";
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
