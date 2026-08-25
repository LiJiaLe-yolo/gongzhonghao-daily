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
    private static final int TARGET_MIN = 2000;
    private static final int TARGET_MAX = 3000;
    private static final int MAX_TOKENS = 8192;
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

    public static void main(String[] args) throws IOException{
        try {
            System.out.println("===== 影评生成任务启动 =====");
            checkEnv();
            List<String> usedMovies = loadUsedFromGist();
            System.out.println("已处理电影数量：" + usedMovies.size());

            String pickedMovie = pickOneMovie(usedMovies);
            System.out.println("选中电影：" + pickedMovie + "｜风格标签：" + currentFilmTag);

            String article = generateReview(pickedMovie);
            System.out.println("生成稿件长度：" + article.length());

            sendFeishuCard(pickedMovie, article);
            System.out.println("飞书推送完成");

            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
            System.out.println("Gist已更新，任务结束");
        } catch (Exception e) {
            System.err.println("任务异常：" + e.getMessage());
            e.printStackTrace();
            try {
                sendFeishuText("【影评任务失败】异常信息：" + e.getMessage());
            } catch (Exception ignored) {
            }
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
            return aiGenerateTaggedMoviePool();
        }
        return list.isEmpty() ? aiGenerateTaggedMoviePool() : list;
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
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static String generateReview(String movieName) throws Exception {
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            String prompt = "请为电影《" + movieName + "》撰写一篇2000-3000字的公众号爆款深度影评，影片核心风格标签：【" + currentFilmTag + "】，严格遵守以下所有写作规范，禁止违规输出：\n" +
                    "\n" +
                    "一、核心定位：拒绝小学生剧情复述，以「成年人现实共鸣+人性深度解读」为核心，贴合标签调性，观点犀利、共情力拉满，适配公众号传播，自带爆款属性。\n" +
                    "二、文章结构（必须严格执行）：\n" +
                    "1. 开篇钩子：用一句扎心、戳中当代年轻人痛点的现实金句引入，瞬间抓住读者，不铺垫剧情；\n" +
                    "2. 极简剧情铺垫：用100字以内简述核心主线，只讲关键冲突，不堆砌细节、不流水账；\n" +
                    "3. 深度内核解读：结合影片镜头、人物选择、剧情隐喻，拆解电影背后的人性、社会、成长、遗憾等深层逻辑，贴合对应风格标签；\n" +
                    "4. 现实共鸣延伸：从电影落地到普通人的生活、职场、情感、内耗、成长困境，让读者代入自身，产生共情；\n" +
                    "5. 观点总结升华：输出独立价值观，不鸡汤、不空洞，有态度、有思考；\n" +
                    "6. 结尾金句收尾：凝练一句高级金句，提升全文质感，适合读者摘抄转发。\n" +
                    "\n" +
                    "三、行文要求：\n" +
                    "1. 段落简短精致，每段3-5行，适配手机端阅读，无大段密集文字；\n" +
                    "2. 语言温柔又有力量，克制不矫情、深刻不晦涩；\n" +
                    "3. 全程围绕影片风格标签创作，风格统一不跑偏；\n" +
                    "4. 无标题、无前言、无摘要、无后记、无特殊符号、无markdown格式，纯正文，可直接粘贴公众号发布；\n" +
                    "5. 字数严格锁定2000-3000字，不足或超额均无效。";

            String content = callDeepSeek(prompt);
            content = content.trim();
            if (content.length() >= TARGET_MIN && content.length() <= TARGET_MAX) {
                return content;
            }
            System.out.printf("稿件长度不达标，重试生成，当前长度：%d%n", content.length());
        }
        throw new Exception("多次生成无法得到符合长度的爆款影评");
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

    private static void sendFeishuCard(String movie, String article) throws IOException {
        String text = article.length() > 2800 ? article.substring(0, 2800) + "\n……（内容截断，完整稿件见程序输出）" : article;
        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONObject ele = new JSONObject();
        ele.put("tag", "div");
        ele.put("text", JSONObject.of("tag", "lark_md", "content", "**🎬《" + movie + "》影评**\n**影片风格：" + currentFilmTag + "**\n\n" + text));
        JSONArray elements = new JSONArray();
        elements.add(ele);
        JSONObject payload = JSONObject.of("config", JSONObject.of("wide_screen_mode", true), "elements", elements);
        card.put("card", payload);
        RequestBody rb = RequestBody.create(card.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(rb).build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("飞书推送异常：" + resp.code() + " " + resp.body().string());
            }
        }
    }

    private static void sendFeishuText(String txt) throws IOException {
        JSONObject jo = JSONObject.of("msg_type", "text", "content", JSONObject.of("text", txt));
        RequestBody rb = RequestBody.create(jo.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(rb).build();
        try(Response resp = HTTP_CLIENT.newCall(req).execute()){
            // ignore
        }
    }
}
