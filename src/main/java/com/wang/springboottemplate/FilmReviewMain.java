package com.wang.springboottemplate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    // 适配飞书单卡片 lark_md 上限，整体安全上限2300字符，影评正文控制 1100‑1500汉字
    private static final int ARTICLE_MIN = 2100;
    private static final int ARTICLE_MAX = 2500;
    private static final int FEISHU_CARD_SAFE_MAX = 2550;

    private static final int MAX_TOKENS = 4096;
    private static final int DEEPSEEK_RETRY = 2;
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
            .readTimeout(90, TimeUnit.SECONDS)
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

            // 全部内容放入单张飞书卡片，不再额外text消息
            sendFeishuCard(pickedMovie, reviewResult);

            System.out.println("飞书卡片推送完成");
            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
            System.out.println("Gist已更新，任务结束");
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
            } else {
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
                "2. 避开烂片、冷门小众片，全部是大众熟知、适合深度解读、自带流量的爆款潜质影片；\n" +
                "3. 只输出纯净JSON数组，不要解释、不要序号、不要多余文字。";
        String resp = callDeepSeek(prompt);
        resp = stripCodeBlock(resp);
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static ReviewResult generateReview(String movieName) throws Exception {
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            String prompt = "你是资深专业影评人，为电影《" + movieName + "》创作，影片风格标签【" + currentFilmTag + "】。\n" +
                    "⚠️重要：**只返回纯净JSON，绝对不要加```json代码块、不要任何解释、不要前置后置文字**。\n" +
                    "JSON结构：{\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"完整影评正文\"}\n" +
                    "\n" +
                    "titles：3个公众号爆款标题，带情绪钩子，适合影视号传播。\n" +
                    "article影评正文：\n" +
                    "1. 专业影评人风格，重镜头隐喻、人物困境、人性思辨；拒绝大段剧情复述，剧情极简点到为止。\n" +
                    "2. 开篇钩子切入，拆解内核，延伸现实共情，结尾金句收束；段落简短适合手机阅读。\n" +
                    "3. 不要markdown格式，纯文本；**严格控制1100‑1500汉字，不能更长**。";

            String contentRaw = callDeepSeek(prompt);
            // 清洗：去掉大模型喜欢输出的 ```json / ``` 代码块标记
            contentRaw = stripCodeBlock(contentRaw).trim();
            System.out.println("AI返回原始JSON片段：" + contentRaw.substring(0, Math.min(200, contentRaw.length())));

            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.out.printf("JSON解析失败，重试，err=%s%n", e.getMessage());
                continue;
            }

            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            if(article == null || titleArr == null || titleArr.size()!=3){
                System.out.println("字段缺失，重试生成");
                continue;
            }
            if (article.length() >= ARTICLE_MIN && article.length() <= ARTICLE_MAX) {
                ReviewResult res = new ReviewResult();
                res.titles = titleArr.toList(String.class);
                res.article = article;
                return res;
            }
            System.out.printf("稿件长度不达标，重试生成，当前长度：%d%n", article.length());
        }
        throw new Exception("多次生成无法得到符合长度的影评");
    }

    /**
     * 去除 ```json ... ``` 代码块包裹
     */
    private static String stripCodeBlock(String text){
        String s = text.trim();
        if(s.startsWith("```")){
            int firstNewLine = s.indexOf('\n');
            int lastBackTick = s.lastIndexOf("```");
            if(lastBackTick > firstNewLine){
                s = s.substring(firstNewLine+1, lastBackTick);
            }
        }
        return s.trim();
    }

    private static String callDeepSeek(String prompt) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", DEEPSEEK_MODEL);
        body.put("max_tokens", MAX_TOKENS);
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
        for (int r = 0; r <= DEEPSEEK_RETRY; r++) {
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                String raw = resp.body().string();
                if (!resp.isSuccessful()) {
                    throw new IOException("DeepSeek调用失败 code=" + resp.code() + " body=" + raw);
                }
                JSONObject jo = JSON.parseObject(raw);
                return jo.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim();
            } catch (IOException e) {
                lastEx = e;
                System.err.printf("DeepSeek调用异常，第%d次重试：%s%n", r+1, e.getMessage());
            }
        }
        throw new IOException("DeepSeek重试耗尽", lastEx);
    }

    private static List<String> loadUsedFromGist() throws IOException {
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("读取Gist失败 " + resp.code());
            }
            JSONObject gist = JSON.parseObject(resp.body().string());
            JSONObject files = gist.getJSONObject("files");
            if(files == null || !files.containsKey(GIST_FILENAME)){
                System.out.println("Gist内目标文件不存在，初始化空已使用列表");
                return new ArrayList<>();
            }
            JSONObject fileObj = files.getJSONObject(GIST_FILENAME);
            String content = fileObj.getString("content");
            return JSON.parseArray(content).toList(String.class);
        }
    }

    private static void saveUsedToGist(List<String> list) throws IOException {
        JSONObject fileItem = new JSONObject();
        fileItem.put("content", JSON.toJSONString(list));
        JSONObject files = new JSONObject();
        files.put(GIST_FILENAME, fileItem);
        JSONObject body = new JSONObject();
        body.put("files", files);
        RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url("https://api.github.com/gists/" + GIST_ID)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .patch(rb)
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("更新Gist失败 code=" + resp.code());
            }
        }
    }

    /**
     * 单张飞书卡片：元信息 + 3个标题 + 完整影评，内置安全字符截断，防止超出飞书lark_md上限
     */
    private static void sendFeishuCard(String movie, ReviewResult reviewResult) throws IOException {
        String titleBlock = "**备选标题：**\n" + String.join("\n", reviewResult.titles);
        StringBuilder mdSb = new StringBuilder();
        mdSb.append("**🎬《").append(movie).append("》影评产出**\n");
        mdSb.append("**影片风格：").append(currentFilmTag).append("**\n\n");
        mdSb.append(titleBlock).append("\n\n");
        mdSb.append("**完整影评正文**\n");
        mdSb.append(reviewResult.article);

        // 安全截断，保证不超过飞书lark_md上限
        String mdContent = mdSb.toString();
        if(mdContent.length() > FEISHU_CARD_SAFE_MAX){
            mdContent = mdContent.substring(0, FEISHU_CARD_SAFE_MAX - 80) + "\n……（篇幅受限截断）";
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
            if (!resp.isSuccessful()) {
                System.err.println("飞书卡片推送异常：code=" + resp.code());
            }
        }
    }
}
