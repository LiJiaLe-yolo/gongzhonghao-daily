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

    // ============ 全部从环境变量读取，禁止硬编码密钥 ============
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String FEISHU_WEBHOOK = System.getenv("FEISHU_WEBHOOK");
    private static final String TMDB_API_KEY = System.getenv("TMDB_API_KEY");

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";

    // Gist持久化已使用电影
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String GITHUB_PAT = System.getenv("GITHUB_PAT");
    private static final String GIST_FILENAME = "film_used_movies.json";

    // 业务参数
    private static final double TMDB_MIN_VOTE = 6.8;
    private static final int TARGET_MIN = 2000;
    private static final int TARGET_MAX = 3000;
    private static final int MAX_TOKENS = 8192;
    private static final int DEEPSEEK_RETRY = 2;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) {
        try {
            System.out.println("===== 影评生成任务启动 =====");
            checkEnv();

            List<String> usedMovies = loadUsedFromGist();
            System.out.println("已处理电影数量：" + usedMovies.size());

            String pickedMovie = pickOneMovie(usedMovies);
            System.out.println("选中电影：" + pickedMovie);

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

    /**
     * 挑选电影，优先TMDB，TMDB为空则AI生成候选池
     */
    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            List<String> candidates;
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                candidates = fetchTmdbMovies();
            } else {
                candidates = aiGenerateMoviePool();
            }
            for (String name : candidates) {
                if (!used.contains(name)) {
                    return name;
                }
            }
            System.out.println("本轮候选全部已使用，重新获取电影池");
        }
        throw new Exception("多次尝试找不到未使用电影");
    }

    private static List<String> fetchTmdbMovies() throws IOException {
        List<String> list = new ArrayList<>();
        HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                .newBuilder()
                .addQueryParameter("api_key", TMDB_API_KEY)
                .addQueryParameter("language", "zh-CN")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) return aiGenerateMoviePool();
            JSONObject json = JSON.parseObject(resp.body().string());
            JSONArray results = json.getJSONArray("results");
            for (Object o : results) {
                JSONObject obj = (JSONObject) o;
                double vote = obj.getDoubleValue("vote_average");
                String title = obj.getString("title");
                if (vote >= TMDB_MIN_VOTE && title != null && !title.isBlank()) {
                    list.add(title);
                }
            }
        }
        return list.size() > 0 ? list : aiGenerateMoviePool();
    }

    private static List<String> aiGenerateMoviePool() throws IOException {
        String prompt = "输出10部口碑高分经典电影名称，只输出JSON数组，不要其他文字。";
        String resp = callDeepSeek(prompt);
        JSONArray arr = JSON.parseArray(resp);
        return arr.toList(String.class);
    }

    private static String generateReview(String movieName) throws Exception {
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            String prompt = "请为电影《" + movieName + "》写一篇深度影评，字数严格控制在"
                    + TARGET_MIN + "-" + TARGET_MAX + "字。视角深刻，叙事流畅，不要标题，不要前言后记，直接输出正文。";
            String content = callDeepSeek(prompt);
            if (content.length() >= TARGET_MIN && content.length() <= TARGET_MAX) {
                return content;
            }
            System.out.println("稿件长度不达标，重试生成，当前长度：" + content.length());
        }
        throw new Exception("多次生成无法得到符合长度的影评");
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
                if (r == DEEPSEEK_RETRY) throw e;
            }
        }
        throw new IOException("DeepSeek重试耗尽");
    }

    /**
     * Gist读取已使用列表
     */
    private static List<String> loadUsedFromGist() throws IOException {
        String url = "https://api.github.com/gists/" + GIST_ID;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .get()
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("读取Gist失败 " + resp.code());
            JSONObject gist = JSON.parseObject(resp.body().string());
            JSONObject files = gist.getJSONObject("files");
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
     * 飞书富卡片推送
     */
    private static void sendFeishuCard(String movie, String article) throws IOException {
        String text = article.length() > 2800 ? article.substring(0, 2800) + "\n……（内容截断）" : article;
        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONObject ele = new JSONObject();
        ele.put("tag", "div");
        ele.put("text", JSONObject.of("tag", "lark_md", "content", "**🎬《" + movie + "》影评**\n\n" + text));

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
        HTTP_CLIENT.newCall(req).execute().close();
    }
}
