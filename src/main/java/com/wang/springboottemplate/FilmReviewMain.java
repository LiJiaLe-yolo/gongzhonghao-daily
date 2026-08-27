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
            "现实扎心、人间百态",
            "社会讽刺、现实隐喻",
            "底层生活、人间真实",
            "时代缩影、众生皆苦",
            "市井烟火、平凡众生",
            "阶层现实、生活真相",
            "人性深度、善恶博弈",
            "自我救赎、与己和解",
            "人性弱点、现实拷问",
            "平凡人性、微光治愈",
            "人心复杂、世事难料",
            "执念放下、人生释然",
            "青春成长、遗憾治愈",
            "年少懵懂、成长阵痛",
            "时光怀旧、岁月温柔",
            "成长取舍、直面人生",
            "少年心事、岁岁念念",
            "青春落幕、各自奔赴",
            "温情治愈、治愈内耗",
            "人间温暖、治愈疲惫",
            "平凡烟火、生活温柔",
            "救赎治愈、抚平焦虑",
            "岁月静好、温柔自愈",
            "微小善意、人间微光",
            "亲情羁绊、烟火人间",
            "爱情遗憾、岁岁年年",
            "原生家庭、成长突围",
            "陪伴守护、平凡幸福",
            "人间情爱、烟火余生",
            "相守平凡、岁岁温柔",
            "人生百态、世事通透",
            "岁月沉淀、人间清醒",
            "平凡人生、万般值得",
            "生活感悟、人间烟火",
            "得失随缘、人生释然",
            "慢品人间、岁月温柔"
    };

    // ================= Prompt 模板 =================
    private static final String MAIN_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
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
            "Step2 产出3条公众号爆款标题，覆盖共鸣式、反差冲突式、提问钩子式，禁止\"XX观后感\"\"浅析XX\"。\n" +
            "Step3 设计开头钩子：100字以内，情绪/悬念切入，不要堆砌导演幕后资料。\n" +
            "Step4 正文四段式骨架：\n" +
            "①开篇入题抛出中心观点\n" +
            "②精简剧情铺垫控制200字以内，只写支撑观点的真实关键情节，禁止完整复述全片\n" +
            "③主体解读（占全文60%%篇幅），拆分3‑4个解读角度；每一个观点绑定影片真实细节；结尾落地普通人现实感悟；拿不准细节直接舍弃。长文在40%%‑60%%位置设置一处阅读钩子反问。\n" +
            "④结尾升华，输出可摘抄金句；结尾使用一句有力的反问句引发读者内心思考，自然收束全文。\n" +
            "Step5 去AI味润色：避免机械排比、模板化升华、空洞形容词；长短句交错；全文至少包含2处反问句；拒绝AI套话诸如引人深思、值得一看。\n" +
            "Step6 公众号排版约束：每段不宜过长，适配手机阅读；必须将中心论点、核心金句、强烈情绪共鸣的句子使用 Markdown 的 加粗 语法进行高亮展示；少写镜头语言、剪辑配乐等专业术语。\n" +
            "\n" +
            "🚫公众号合规铁律：正文不要放链接、微信号；不要出现\"点赞转发收藏\"指令；严禁出现\"评论区聊聊\"\"评论区等你\"\"欢迎留言\"\"你怎么看，欢迎讨论\"等任何引导读者去评论区互动的套话。结尾的反问句仅用于引发读者内心思考，不要引导互动。\n" +
            "\n" +
            "✅【输出JSON强制格式，只输出JSON，禁止、禁止注释、禁止额外说明】\n" +
            "{\n" +
            " \"centralArgument\":\"一句话中心论点\",\n" +
            " \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            " \"article\":\"完整公众号markdown正文，保留加粗语法\"\n" +
            "}\n" +
            "\n" +
            "为电影《%s》撰写公众号影评，影片风格标签【%s】。\n" +
            "【硬性字数强制】正文汉字必须严格控制在1800‑2500，字数不足直接作废本次输出；不清楚的影片细节绝不编造，只返回JSON。";

    private static final String FALLBACK_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守】\n" +
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
            "5.必须将核心金句、情绪共鸣点使用 Markdown 的 加粗 语法高亮。\n" +
            "6.【硬性强制】正文汉字严格1800‑2500，字数不够直接作废。\n" +
            "7.严禁出现\"评论区聊聊\"\"评论区等你\"\"欢迎留言\"等引导评论区互动的套话。结尾反问仅用于引发思考，不引导互动。\n" +
            "\n" +
            "✅输出JSON格式，禁止代码块、多余文字：\n" +
            "{\n" +
            " \"centralArgument\":\"中心论点\",\n" +
            " \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            " \"article\":\"正文markdown\"\n" +
            "}\n" +
            "\n" +
            "电影《%s》，风格标签【%s】。只输出JSON。";

    private static final String EXPAND_REVIEW_PROMPT_TPL = "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
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
            "Step6 手机阅读短段落，必须将中心论点、核心金句、强烈情绪共鸣的句子使用 Markdown 的 加粗 语法进行高亮展示，剔除AI套话。\n" +
            "\n" +
            "🚫禁止链接、导流话术；严禁出现\"评论区聊聊\"\"评论区等你\"\"欢迎留言\"等引导评论区互动的套话。结尾反问仅用于引发读者内心思考，不引导互动。\n" +
            "【硬性强制】正文严格1800‑2500字符，必须达到该区间。允许现实感悟充分延展，但绝对不能编造电影里不存在的情节。\n" +
            "\n" +
            "✅仅输出JSON，不要代码块，不要额外文字：\n" +
            "{\n" +
            " \"centralArgument\":\"一句话中心论点\",\n" +
            " \"titles\":[\"标题1\",\"标题2\",\"标题3\"],\n" +
            " \"article\":\"markdown正文\"\n" +
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
            sendFeishuCard(pickedMovie, reviewResult, articleRawLength, currentTmdbMovieInfo != null);

            usedMovies.add(pickedMovie);
            saveUsedToGist(usedMovies);
            System.out.println("\n🎉 任务正常结束，Gist已保存已处理影片列表！");

        } catch (Exception e) {
            System.err.println("\n💥 任务异常：" + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void checkEnv() throws Exception {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isBlank()) throw new Exception("环境变量 DEEPSEEK_API_KEY 未配置");
        if (FEISHU_WEBHOOK == null || FEISHU_WEBHOOK.isBlank()) throw new Exception("环境变量 FEISHU_WEBHOOK 未配置");
        if (GIST_ID == null || GITHUB_PAT == null || GIST_ID.isBlank() || GITHUB_PAT.isBlank()) throw new Exception("GIST_ID / GITHUB_PAT 未配置");
    }

    private static String autoMapFilmTagByGenres(TmdbMovieInfo info) {
        if (info == null || info.genres == null || info.genres.isEmpty()) {
            String tag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.printf("   🏷️ [标签映射] 无类型信息，随机标签=%s\n", tag);
            return tag;
        }
        List<Integer> genreIds = new ArrayList<>();
        info.genres.forEach(g -> genreIds.add(g.id));
        if (genreIds.contains(18) && genreIds.contains(80)) return "社会讽刺、现实隐喻";
        if (genreIds.contains(18) || genreIds.contains(9648)) return "现实扎心、人间百态";
        if (genreIds.contains(80)) return "人性深度、善恶博弈";
        if (genreIds.contains(10749)) return "青春成长、遗憾治愈";
        if (genreIds.contains(10751)) return "亲情羁绊、烟火人间";
        if (genreIds.contains(35)) return "温情治愈、治愈内耗";
        List<String> fallbackTags = new ArrayList<>();
        fallbackTags.add("人性深度、自我救赎");
        fallbackTags.add("底层生活、人间真实");
        fallbackTags.add("平凡人性、微光治愈");
        fallbackTags.add("成长取舍、直面人生");
        fallbackTags.add("人生百态、世事通透");
        fallbackTags.add("岁月沉淀、人间清醒");
        fallbackTags.add("市井烟火、平凡众生");

        String tag = fallbackTags.get(ThreadLocalRandom.current().nextInt(fallbackTags.size()));
        System.out.printf("   🏷️ [标签映射] 进入兜底标签池，选中=%s\n", tag);
        return tag;
    }

    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            System.out.println("\n" + "=".repeat(60));
            System.out.printf("🔄 [选片主循环] 第 %d/%d 次全局选片尝试\n", i + 1, PICK_MAX_RETRY);
            System.out.println("=".repeat(60));
            // ================= 阶段一：TMDB 热映/热门 + AI 优选 =================
            System.out.println("\n👉 【阶段一】尝试从 TMDB 获取近期热门/上映电影，交由 AI 优选...");
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                List<TmdbMovieInfo> tmdbCandidateList = tryPickFromTmdbNowPlaying();
                System.out.printf("   📊 TMDB 初始拉取并校验后的候选数量: %d\n", tmdbCandidateList.size());

                if (!tmdbCandidateList.isEmpty()) {
                    List<TmdbMovieInfo> filterCandidates = new ArrayList<>();
                    for (TmdbMovieInfo info : tmdbCandidateList) {
                        String title = info.title != null ? info.title : info.originalTitle;
                        boolean isHorrorOrThriller = info.genres.stream().anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                        if (!isBlackMovie(title) && !used.contains(title) && !isHorrorOrThriller) {
                            filterCandidates.add(info);
                        } else {
                            System.out.printf("   🚫 业务过滤: %s (原因: %s)\n", title,
                                    isBlackMovie(title) ? "黑名单/已使用" : (isHorrorOrThriller ? "恐怖/惊悚题材" : "未知"));
                        }
                    }
                    System.out.printf("   ✅ 业务过滤后有效候选池大小: %d\n", filterCandidates.size());

                    if (!filterCandidates.isEmpty()) {
                        System.out.println("   🤖 正在调用 AI 从候选池中挑选最适合公众号深度影评的电影...");
                        Long selectMovieId = aiSelectBestFilmFromTmdbCandidates(filterCandidates);
                        if (selectMovieId != null) {
                            for (TmdbMovieInfo info : filterCandidates) {
                                if (info.id == selectMovieId) {
                                    currentTmdbMovieInfo = info;
                                    currentFilmTag = autoMapFilmTagByGenres(info);
                                    System.out.printf("   🎉 【阶段一成功】AI 优选命中！电影: %s | 自动匹配标签: %s\n", info.title, currentFilmTag);
                                    return info.title;
                                }
                            }
                        } else {
                            System.out.println("   ⚠️ AI 认为当前 TMDB 候选池中无合适影片，或解析失败，准备进入阶段二...");
                        }
                    }
                }
            } else {
                System.out.println("   ⚠️ 未配置 TMDB_API_KEY，跳过阶段一。");
            }
            // ================= 阶段二：随机 Tag + AI 推经典片 + TMDB 验证 =================
            System.out.println("\n👉 【阶段二】TMDB 优选失败，启用【随机业务标签 -> AI 推荐经典影片 -> TMDB 搜索验证】策略...");
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.printf("   🎲 随机选中的业务标签: 【%s】\n", currentFilmTag);

            System.out.println("   🤖 正在调用 AI 根据标签推荐经典高分电影...");
            List<String> classicCandidates = aiGetClassicMovieNamesByTag(currentFilmTag);
            System.out.printf("   📜 AI 返回候选片名列表: %s\n", classicCandidates);

            boolean foundInClassic = false;
            for (String candidateName : classicCandidates) {
                if (isBlackMovie(candidateName) || used.contains(candidateName)) {
                    System.out.printf("   🚫 跳过候选: %s (黑名单或已使用)\n", candidateName);
                    continue;
                }
                System.out.printf("   🔍 正在 TMDB 搜索验证候选影片: %s ...\n", candidateName);
                TmdbMovieInfo searchInfo = tmdbSearchMovie(candidateName);
                if (searchInfo == null) {
                    System.out.printf("   ❌ TMDB 搜索无结果，放弃该候选: %s\n", candidateName);
                    continue;
                }
                boolean isHorrorOrThriller = searchInfo.genres.stream().anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                if (isHorrorOrThriller) {
                    System.out.printf("   🚫 跳过候选: %s (TMDB验证为恐怖/惊悚题材)\n", candidateName);
                    continue;
                }
                if (isValidTmdbMovie(searchInfo)) {
                    currentTmdbMovieInfo = searchInfo;
                    currentFilmTag = autoMapFilmTagByGenres(searchInfo);
                    System.out.printf("   🎉 【阶段二成功】经典库选片成功！电影: %s | 重新映射标签: %s\n", searchInfo.title, currentFilmTag);
                    return searchInfo.title;
                } else {
                    System.out.printf("   ❌ TMDB 校验不通过(评分低或简介短): %s\n", candidateName);
                }
            }
            if (!foundInClassic) {
                System.out.println("   ⚠️ 阶段二所有 AI 推荐经典影片均未通过 TMDB 验证，准备进入阶段三...");
            }
            // ================= 阶段三：纯 AI 兜底 (无 TMDB 验证) =================
            System.out.println("\n👉 【阶段三】TMDB 搜索验证全部失败，启用【纯 AI 兜底生成片单】策略 (风险较高)...");
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            System.out.printf("   🎲 重新随机业务标签: 【%s】\n", currentFilmTag);

            System.out.println("   🤖 正在调用 AI 生成兜底电影池...");
            List<String> aiPool = aiGenerateTaggedMoviePool();
            List<String> safe = new ArrayList<>();
            for (String n : aiPool) {
                if (!isBlackMovie(n) && !used.contains(n)) safe.add(n);
            }
            System.out.printf("   🛡️ AI 兜底池过滤后可用片名: %s\n", safe);

            if (!safe.isEmpty()) {
                currentTmdbMovieInfo = null;
                String finalPick = safe.get(0);
                System.out.printf("   🎉 【阶段三成功】AI 兜底选片成功！电影: %s (无 TMDB 数据支撑)\n", finalPick);
                return finalPick;
            }

            System.out.println("   ❌ 阶段三也未找到可用影片，本次全局选片尝试失败。");
        }
        throw new Exception("多次尝试(全局重试耗尽)仍找不到未使用安全电影，请扩充候选池或清理Gist记录");
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
                "下面是一批候选电影JSON数组，请从中挑选最适合写公众号深度影评的一部。\n" +
                "筛选标准：\n" +
                "1.有话题度，适合挖掘人性、现实、情绪共鸣、人生感悟，适配多种文风，不要纯爆米花爽片；\n" +
                "2.简介信息充足，有足够解读空间；\n" +
                "3.已经前置过滤恐怖惊悚鬼怪题材，候选列表无恐怖片；\n" +
                "4.输出要求：只输出JSON，格式 {\"selectedId\":数字}。\n" +
                "如果这批全部都不适合做公众号深度影评，则输出 {\"selectedId\":null}。\n" +
                "候选列表：\n" + jsonArr;
        System.out.println("   📤 [AI选片] 发送的候选JSON: " + jsonArr.toJSONString());
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        System.out.println("   📥 [AI选片] 大模型原始返回: " + resp);

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
            System.err.println("   ⚠️ [WARN] 候选选片解析失败:" + e.getMessage());
            return null;
        }
    }

    private static List<TmdbMovieInfo> tryPickFromTmdbNowPlaying() {
        List<Long> idList = new ArrayList<>();
        System.out.println("\n   🌐 [TMDB] 开始请求 now_playing 热映列表...");
        try {
            HttpUrl nowPlayingUrl = HttpUrl.parse(TMDB_BASE + "/movie/now_playing")
                    .newBuilder().addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").build();
            Request reqNow = new Request.Builder().url(nowPlayingUrl).get().build();
            try (Response resp = HTTP_CLIENT.newCall(reqNow).execute()) {
                if (resp.isSuccessful()) {
                    JSONObject json = JSON.parseObject(resp.body().string());
                    JSONArray results = json.getJSONArray("results");
                    if (results != null && !results.isEmpty()) {
                        System.out.printf("   📊 [TMDB] now_playing 接口返回总数量: %d\n", results.size());
                        for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            long mid = obj.getLongValue("id");
                            String title = obj.getString("title");
                            double vote = obj.getDoubleValue("vote_average");
                            if (vote >= TMDB_MIN_VOTE) {
                                idList.add(mid);
                                System.out.printf("      ✅ 保留: ID=%-6d | 评分=%.1f | 片名=%s\n", mid, vote, title);
                            } else {
                                System.out.printf("      ❌ 过滤: ID=%-6d | 评分=%.1f | 片名=%s (原因: 评分低于%.1f)\n", mid, vote, title, TMDB_MIN_VOTE);
                            }
                        }
                    } else {
                        System.out.println("   ⚠️ [TMDB] now_playing 接口返回结果为空");
                    }
                } else {
                    System.err.printf("   ❌ [TMDB] now_playing 请求失败, HTTP Code: %d\n", resp.code());
                }
            }
        } catch (Exception e) {
            System.err.println("   ❌ [TMDB] now_playing 请求异常: " + e.getMessage());
        }
        if (idList.size() < 6) {
            System.out.printf("\n   🌐 [TMDB] now_playing 保留数量(%d)不足6部，开始补充 popular 热门列表...\n", idList.size());
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/popular")
                        .newBuilder().addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").build();
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        JSONObject json = JSON.parseObject(resp.body().string());
                        JSONArray results = json.getJSONArray("results");
                        if (results != null && !results.isEmpty()) {
                            System.out.printf("   📊 [TMDB] popular 接口返回总数量: %d\n", results.size());
                            for (Object o : results) {
                                JSONObject obj = (JSONObject) o;
                                long mid = obj.getLongValue("id");
                                String title = obj.getString("title");
                                double vote = obj.getDoubleValue("vote_average");
                                if (vote >= TMDB_MIN_VOTE && !idList.contains(mid)) {
                                    idList.add(mid);
                                    System.out.printf("      ✅ 补充: ID=%-6d | 评分=%.1f | 片名=%s\n", mid, vote, title);
                                } else if (vote < TMDB_MIN_VOTE) {
                                    System.out.printf("      ❌ 过滤: ID=%-6d | 评分=%.1f | 片名=%s (原因: 评分低于%.1f)\n", mid, vote, title, TMDB_MIN_VOTE);
                                }
                            }
                        }
                    } else {
                        System.err.printf("   ❌ [TMDB] popular 请求失败, HTTP Code: %d\n", resp.code());
                    }
                }
            } catch (Exception e) {
                System.err.println("   ❌ [TMDB] popular 请求异常: " + e.getMessage());
            }
        }
        System.out.printf("\n   📝 [TMDB] 准备拉取详情的 ID 列表 (共%d个): %s\n", idList.size(), idList);
        List<TmdbMovieInfo> validList = new ArrayList<>();
        for (long mid : idList) {
            TmdbMovieInfo info = tmdbGetMovieDetail(mid);
            if (isValidTmdbMovie(info)) {
                validList.add(info);
                System.out.printf("      ✅ 详情校验通过: %s (简介长度: %d)\n", info.title, info.overview.length());
            } else {
                System.out.printf("      ❌ 详情校验失败: ID=%d (原因: 简介过短或评分不足)\n", mid);
            }
        }
        validList.sort(Comparator.comparingInt((TmdbMovieInfo m) -> m.overview.length()).reversed()
                .thenComparingDouble(m -> m.voteAverage).reversed());

        if (!validList.isEmpty()) {
            System.out.println("   🏆 [TMDB] 排序后的 Top 3 候选影片:");
            for (int i = 0; i < Math.min(3, validList.size()); i++) {
                TmdbMovieInfo m = validList.get(i);
                System.out.printf("      %d. %s (评分: %.1f, 简介长度: %d)\n", i + 1, m.title, m.voteAverage, m.overview.length());
            }
        }
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
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("   ⚠️ [WARN] 经典影片获取失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static TmdbMovieInfo tmdbSearchMovie(String movieName) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/search/movie")
                    .newBuilder().addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").addQueryParameter("query", movieName).build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.printf("      ❌ [TMDB搜索] HTTP请求失败, Code: %d\n", resp.code());
                    return null;
                }
                JSONObject jo = JSON.parseObject(resp.body().string());
                JSONArray results = jo.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    System.out.printf("      ⚠️ [TMDB搜索] 未找到相关影片: %s\n", movieName);
                    return null;
                }
                long mid = results.getJSONObject(0).getLongValue("id");
                System.out.printf("      🔍 [TMDB搜索] 命中影片 ID: %d\n", mid);
                return tmdbGetMovieDetail(mid);
            }
        } catch (Exception e) {
            System.err.println("      ❌ 影片搜索异常:" + e.getMessage());
            return null;
        }
    }

    private static TmdbMovieInfo tmdbGetMovieDetail(long movieId) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/" + movieId)
                    .newBuilder().addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.printf("      ❌ [TMDB详情] HTTP请求失败, ID: %d, Code: %d\n", movieId, resp.code());
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
                return info;
            }
        } catch (Exception e) {
            System.err.println("      ❌ 影片详情获取异常:" + e.getMessage());
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
        String prompt = "你是公众号影视选题编辑，请根据风格标签【" + currentFilmTag + "】，输出10部真实上映的高分电影中文片名。\n" +
                "硬性约束：\n" +
                "1.禁止编造不存在影片，片名必须准确；过滤恐怖、惊悚、鬼怪题材。\n" +
                "2.贴合标签调性，适合公众号深度影评，大众认知度高或小众优质高分影片均可。\n" +
                "3.只输出纯净JSON数组，不要任何解释、序号。\n" +
                "输出：[\"电影1\",\"电影2\"]";
        String resp = callDeepSeek(prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL);
        resp = stripCodeBlock(resp);
        try {
            return JSON.parseArray(resp).toList(String.class);
        } catch (Exception e) {
            System.err.println("   ⚠️ [WARN] 兜底片库生成失败:" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        int emptyCount = 0;
        String safeOverview = (tmdbOverview == null || tmdbOverview.isBlank()) ? "" : tmdbOverview.replace("“", "\"").replace("”", "\"");
        boolean weakOverview = safeOverview.length() < OVERVIEW_WEAK_THRESHOLD;
        System.out.printf("\n✍️ [生成器] TMDB简介长度：%d，素材判定为薄弱=%b\n", safeOverview.length(), weakOverview);
        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("\n🔄 [影评生成] 第 %d/%d 轮尝试...\n", i + 1, ARTICLE_MAX_RETRY);
            String prompt;
            int currentMaxToken;
            double currentTemp;
            String modeName;
            if (weakOverview) {
                prompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_EXPAND;
                currentTemp = TEMPERATURE_EXPAND;
                modeName = "扩写加强模式";
            } else {
                prompt = i < 2 ? String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag) : String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag);
                currentMaxToken = MAX_TOKENS_NORMAL;
                currentTemp = TEMPERATURE_NORMAL;
                modeName = i < 2 ? "普通生成模式" : "降级兜底模式";
            }
            System.out.printf("   📝 使用 Prompt 模式: %s\n", modeName);
            String contentRaw;
            try {
                contentRaw = callDeepSeek(prompt, currentMaxToken, currentTemp);
            } catch (IOException ex) {
                System.err.printf("   ❌ [retry=%d] 网络异常 %s\n", i, ex.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            contentRaw = stripCodeBlock(contentRaw).trim();
            int start = contentRaw.indexOf('{');
            int end = contentRaw.lastIndexOf('}');
            if (start != -1 && end > start) contentRaw = contentRaw.substring(start, end + 1);
            if (contentRaw.isBlank()) {
                emptyCount++;
                System.err.printf("   ❌ [retry=%d] 返回为空字符串\n", i);
                if (emptyCount >= 3) throw new MovieCannotHandleException("连续空返回");
                sleepRandom(1200, 2500);
                continue;
            }
            JSONObject jo;
            try {
                jo = JSON.parseObject(contentRaw);
            } catch (Exception e) {
                System.err.printf("   ❌ [retry=%d] JSON解析失败:%s\n", i, e.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");
            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                System.err.printf("   ❌ [retry=%d] JSON字段缺失\n", i);
                sleepRandom(1200, 2500);
                continue;
            }
            article = cleanAiArticle(article);
            article = removeInteractionCTA(article);

            ReviewResult temp = new ReviewResult();
            temp.centralArgument = centralArg;
            temp.titles = titleArr.toList(String.class);
            temp.article = article;
            int len = article.length();
            System.out.printf("   📏 本次生成稿件长度: %d 字符 | 目标区间: [%d ~ %d]\n", len, ARTICLE_TARGET_MIN, ARTICLE_TARGET_MAX);
            if (len >= ARTICLE_TARGET_MIN && len <= ARTICLE_TARGET_MAX) {
                System.out.println("   ✅ 命中目标字数区间，影评生成成功！");
                return temp;
            } else {
                System.out.printf("   ❌ 稿件长度不达标，丢弃本稿，准备重试...\n");
            }
            sleepRandom(1200, 2500);
        }
        System.out.println("\n🚨 [保底机制] 常规重试耗尽，执行强制扩写与补长逻辑...");
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
        int finalLen = finalRes.article.length();
        System.out.printf("   🚨 保底轮产出，稿件长度=%d\n", finalLen);
        if (finalLen < ARTICLE_TARGET_MIN) {
            StringBuilder sb = new StringBuilder(finalRes.article);
            while (sb.length() < ARTICLE_TARGET_MIN) {
                sb.append("\n\n很多时候，电影里看见的是别人的故事，映照的却是我们自己一路走来的人生境遇。那些遗憾、挣扎与和解，不止发生在银幕之上，也藏在每一个普通人日复一日的生活之中。");
            }
            finalRes.article = sb.toString();
            System.out.printf("   🚨 兜底文本补长完成，最终长度=%d\n", finalRes.article.length());
        }
        if (finalRes.article.length() > ARTICLE_TARGET_MAX) {
            finalRes.article = finalRes.article.substring(0, ARTICLE_TARGET_MAX);
        }
        return finalRes;
    }

    private static String removeInteractionCTA(String article) {
        if (article == null || article.isBlank()) return article;

        String[] ctaPhrases = {
                "评论区聊聊", "评论区说说", "评论区见", "评论区等你",
                "评论区聊起来", "评论区留言", "评论区告诉我",
                "欢迎留言", "欢迎在评论区", "欢迎讨论", "欢迎分享",
                "留言告诉我", "留言说说", "留言区见", "留言区等你",
                "说说你的看法", "分享你的看法", "分享你的故事",
                "你怎么看？欢迎讨论", "你怎么看，欢迎讨论",
                "你觉得呢？欢迎留言", "你觉得呢，欢迎留言"
        };

        String result = article;
        for (String phrase : ctaPhrases) {
            result = result.replace(phrase, "");
        }

        result = result.replaceAll("\\n{3,}", "\n\n");
        result = result.trim();

        return result;
    }

    private static String cleanAiArticle(String text) {
        if (text == null) return "";
        text = text.replaceAll("【.*?】", "");
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
        Pattern codePattern = Pattern.compile("^```[a-zA-Z0-9]*\\R(.*?)\\R```$", Pattern.DOTALL);
        Matcher matcher = codePattern.matcher(s);
        if (matcher.matches()) {
            s = matcher.group(1).trim();
        }
        return s.trim();
    }

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
                    throw new IOException("接口请求失败：" + resp.code());
                }
                JSONObject jo = JSON.parseObject(raw);
                JSONObject choice0 = jo.getJSONArray("choices").getJSONObject(0);
                JSONObject msgObj = choice0.getJSONObject("message");
                String modelContent = msgObj.getString("content");
                String reasoningContent = msgObj.getString("reasoning_content");
                if ((modelContent == null || modelContent.isBlank()) && reasoningContent != null && !reasoningContent.isBlank()) {
                    System.out.println("   💡 [callDeepSeek] content为空，尝试读取reasoning_content");
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
                System.err.printf("   ❌ [callDeepSeek] 调用异常 retry=%d err=%s\n", r, e.getMessage());
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
    }
}
