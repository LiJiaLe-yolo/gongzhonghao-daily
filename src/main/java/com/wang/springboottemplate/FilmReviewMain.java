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

    private static final int MAX_TOKENS = 3400;
    private static final double TEMPERATURE = 0.40;
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

    // 主影评Prompt，强化禁止编造剧情幻觉
    private static final String MAIN_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
            "角色：资深公众号爆款影评撰稿人，面向普通公众号读者，不是专业影迷，拒绝晦涩学院派话术。\n" +
            "写作底层逻辑：电影只是载体，输出人性、现实痛点、情绪共鸣，提升文章收藏、转发、评论数据，拒绝纯剧情流水账复述。\n" +
            "\n" +
            "🔴最高优先级约束：严禁编造剧情、人物、台词、名场面、细节伏笔。所有引用的情节、对话、人物行为必须是影片客观真实内容，不知道的细节就不要写，禁止脑补杜撰。\n" +
            "\n" +
            "写作规范：\n" +
            "1.选题视角：优先挖掘人物困境、人性思辨、现实映射、细节伏笔；可做反差解读，观点客观克制，不无脑吹捧、不恶意批判影片。\n" +
            "2.标题：输出3条公众号爆款标题，覆盖共鸣式、反差冲突式、提问钩子式；禁止“XX观后感”“浅析XX”这类平淡学术标题。\n" +
            "3.开篇：100字以内，用情绪、悬念、场景代入抓住读者，不要堆砌导演、幕后花絮。\n" +
            "4.正文严格四段式结构：\n" +
            "①开篇入题：抛出核心情绪/核心观点\n" +
            "②精简剧情铺垫：控制200字以内，只选取支撑核心观点的真实关键情节、名场面，禁止完整复述全片剧情，禁止虚构情节。\n" +
            "③主体解读（占全文60%篇幅）：拆分3‑4个解读角度，每一个观点必须绑定电影真实存在的细节、台词、人物行为；每段解读末尾落地映射普通人现实生活感悟，拒绝悬浮空谈。拿不准的影片细节直接舍弃，不要自行编造。\n" +
            "④结尾升华：输出可复制摘抄金句，增加评论区互动引导话术。\n" +
            "\n" +
            "行文格式约束：\n" +
            "- 段落简短，适配手机公众号阅读；关键金句做加粗标记；少聊镜头、剪辑、配乐等专业技术术语，普通读者不关心。\n" +
            "- 禁止空洞鸡汤，所有感悟必须来自影片真实内容。\n" +
            "\n" +
            "输出格式铁则：\n" +
            "1.直接输出闭合JSON对象，绝对禁止```代码块、禁止前后附加说明文字、禁止注释；接口已开启JSON强制输出。\n" +
            "2.正文字符严格控制1800‑2500汉字；到达2500字符必须立刻收尾；不足1800字就增加思辨感悟，严禁靠堆剧情凑字数；超过2500直接截断收尾。\n" +
            "3.固定JSON结构：{\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"完整影评正文文本\"}\n" +
            "4.titles数组严格3个字符串；article为完整正文，正文内部保留markdown加粗语法，不要其他复杂markdown。\n" +
            "\n" +
            "现在为电影《%s》撰写公众号影评，影片风格标签【%s】。\n" +
            "重点：重人物命运、人性思辨、现实共情；剧情只做极简铺垫。绝对禁止杜撰任何影片细节，不清楚就不写。只返回JSON，不要输出JSON以外任何内容。";

    // 兜底降级Prompt，重试后期使用，强化防幻觉
    private static final String FALLBACK_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守】\n" +
            "角色：公众号影评撰稿人，面向普通大众读者。\n" +
            "🔴最高约束：严禁编造剧情、台词、人物细节，所有引用素材必须是影片真实内容，不确定就省略，禁止脑补。\n" +
            "写作逻辑：少复述剧情，多输出人性感悟、现实共鸣，提高收藏转发。\n" +
            "\n" +
            "写作规范：\n" +
            "1.输出3条公众号钩子标题，禁止观后感、浅析类标题。\n" +
            "2.开篇简短抓情绪，剧情部分最大150字，只写真实关键片段，严禁大段讲故事、虚构情节。\n" +
            "3.主体解读3‑4个角度，全部结合影片真实细节，落地普通人生活感受。\n" +
            "4.结尾金句+评论区互动。\n" +
            "5.正文1600‑2200汉字，段落短小适合手机阅读，金句加粗。\n" +
            "\n" +
            "输出格式铁则：\n" +
            "1.只输出闭合JSON，禁止代码块、多余文字。\n" +
            "2.固定结构 {\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"正文\"}\n" +
            "3.titles必须3条。\n" +
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

    /**
     * 优先拉取热映now_playing，热映数量不足再补充popular高分片
     */
    private static List<String> fetchTmdbMovies() {
        List<String> list = new ArrayList<>();
        // 1.优先热映影片，抓热度
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
                            if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank()) {
                                list.add(title);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("拉取热映影片异常：" + e.getMessage());
        }

        // 2.热映池不足，补充popular高分电影，去重
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
                                if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank() && !list.contains(title)) {
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

    private static List<String> aiGenerateTaggedMoviePool() throws IOException {
        int tagIndex = (int) (Math.random() * FILM_TAGS.length);
        currentFilmTag = FILM_TAGS[tagIndex];
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部**真实存在**的国内外高分经典电影中文名称。\n" +
                "硬性约束：\n" +
                "1.所有电影必须真实上映，严禁编造不存在影片，不能记错片名；\n" +
                "2.严格贴合标签风格，题材统一调性一致；\n" +
                "3.影片质量过硬，适合公众号深度解读，具备情绪共鸣、爆款潜质，避开烂片、过度冷门无大众认知的影片；\n" +
                "4.只输出纯净JSON字符串数组，不要任何解释、序号、markdown、多余文字。\n" +
                "输出格式：[\"电影1\",\"电影2\",\"电影3\"]";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static ReviewResult generateReview(String movieName) throws Exception {
        ReviewResult fallbackResult = null;
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("[LOG] 影评生成第%d轮重试%n", i + 1);
            String prompt;
            // 前两轮主prompt，第3轮起切换兜底prompt
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
            System.out.println("AI返回原始JSON片段：" + contentRaw.substring(0, Math.min(220, contentRaw.length())));
            if (contentRaw.isBlank()) {
                System.out.println("[WARN] AI返回为空，休眠后重试");
                sleepRandom(1200, 2500);
                continue;
            }
            if (!contentRaw.startsWith("{") || !contentRaw.endsWith("}")) {
                System.out.println("[WARN] JSON首尾括号不完整，截断输出，丢弃本轮");
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
            if (article == null || titleArr == null || titleArr.size() != 3) {
                System.out.println("[WARN]字段缺失，重试生成");
                sleepRandom(1200, 2500);
                continue;
            }
            ReviewResult temp = new ReviewResult();
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
        body.put("response_format", JSONObject.of("type", "json_object"));
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
