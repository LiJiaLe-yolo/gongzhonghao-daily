package com.wang.springboottemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
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

    private static final int MAX_TOKENS_NORMAL = 16384;
    private static final int MAX_TOKENS_EXPAND = 32768;
    private static final double TEMPERATURE_NORMAL = 0.12;
    private static final double TEMPERATURE_EXPAND = 0.28;

    private static final int DEEPSEEK_MAX_OUTPUT_TOKENS = getEnvInt("DEEPSEEK_MAX_OUTPUT_TOKENS", 16384);
    private static final int DEEPSEEK_NET_RETRY = 1;
    private static final int ARTICLE_MAX_RETRY = 4;
    private static final int PICK_MAX_RETRY = 3;

    private static final int GENRE_HORROR = 27;
    private static final int GENRE_THRILLER = 53;

    private static final String[] MOVIE_BLACKLIST_KEYWORD = {"鬼玩人", "鬼", "驱魔", "电锯", "惊魂", "恐怖", "惊悚"};

    private static final String[] WRITING_ANGLES = {
            "【感性叙事者】：以第一人称视角，像和老朋友深夜聊天一样。语气要感性、走心，少用排比句。侧重于描写看完电影后的情绪波动和内心独白。允许出现不完美的、碎片化的个人感受。",
            "【犀利观察家】：以冷峻的社会观察者视角。语气犀利、直接，直击痛点。不要温吞的感悟，要像手术刀一样剖析人性弱点或社会潜规则。敢于提出冒犯性但真实的观点。",
            "【心理分析师】：以心理学/社会学分析视角。语气客观、专业但通俗。侧重于剖析人物潜意识、原生家庭创伤或群体心理，多用投射、防御机制、依恋模式等概念（需用生活化语言解释）。",
            "【怀旧散文家】：以怀旧、文艺的视角。注重氛围描写，语气温柔、缓慢。将电影情节与逝去的时光、老物件、旧记忆联系起来。多用通感和具象细节，避免抽象抒情。",
            "【毒舌影评人】：以挑剔、幽默的视角。可以适度吐槽剧情逻辑，用幽默化解沉重，但在吐槽背后要有对人性的深刻洞察。讽刺要精准而非刻薄。",
            "【跨学科解读者】：用经济学/哲学/传播学等非影视学科框架解读电影。例如用沉没成本分析角色执念，用景观社会解读视觉符号。让读者获得观影之外的知识增量。",
            "【城市人类学家】：聚焦电影中呈现的空间、阶层、地域文化差异。把电影当作一份田野调查样本，解读其中隐藏的城市生存法则、社区关系变迁或城乡张力。",
            "【代际对话者】：以两代人甚至三代人的认知差异为切入点。探讨同一件事在不同年龄段的截然不同的理解，呈现代沟背后的时代结构性变化，而非简单评判对错。"
    };

    private static final String[] SOCIAL_CONTEXT_POOL = {
            "将电影核心矛盾与【当下职场中的隐性PUA与自我价值感缺失】进行对照分析",
            "将电影情感困境与【社交媒体时代的表演型人格与真实孤独感】进行横向关联",
            "将电影人物抉择与【消费主义裹挟下的身份焦虑与物化倾向】进行深度绑定",
            "将电影家庭冲突与【东亚家庭中未被言说的情感债务与边界感缺失】进行互文解读",
            "将电影命运轨迹与【小镇青年进城后的文化撕裂与归属感悬置】进行现实映射",
            "将电影道德困境与【算法推荐时代的信息茧房与认知窄化】进行类比思辨",
            "将电影亲密关系与【原子化社会中人际连接的脆弱性与重建可能】进行社会学审视",
            "将电影成长叙事与【教育内卷背景下被压缩的自我探索空间】进行代际对比",
            "将电影权力结构与【组织中的沉默螺旋与服从性测试】进行管理学交叉分析",
            "将电影生死命题与【老龄化社会中照护伦理的崩塌与重构】进行现实关照"
    };

    private static final String ANTI_HOMOGENEITY_INJECTION =
            "\n\n🔴【反同质化铁律·违反则输出作废】\n" +
                    "1. 禁止使用以下AI高频套话句式：在这个快节奏的时代、不禁让人深思、值得我们每个人反思、在光影交错中、银幕内外、影片如同一面镜子、引发了广泛共鸣、触动人心最柔软的地方。\n" +
                    "2. 每篇文章必须包含至少一个【具体的、非通用的现实案例】：可以是某条新闻、某个身边人的真实故事、某组数据、某个具体地名/品牌/现象名称。禁止只用有些人、很多人、我们等泛指。\n" +
                    "3. 中心论点必须是【反常识或细分切口】，禁止珍惜当下、勇敢追梦、爱与和解等万能主题。如果选题本身常见，必须找到至少一个前人未写的独特解读维度。\n" +
                    "4. 行文节奏必须有变化：长短句交替，偶尔使用不完整句、口语化插入语、括号补充说明。禁止全文匀速平稳的正确废话节奏。\n" +
                    "5. 至少有一处【作者个人的不确定性表达】：如我不确定这是否准确、也许我的理解是偏颇的、这个问题我自己也没想明白。真人写作不会永远笃定。\n" +
                    "6. 禁止段落开头使用首先/其次/最后/此外/值得注意的是等机械连接词。用内容本身的逻辑衔接代替形式连接。\n";

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

    // [修复] 文本块内的双引号全部替换为中文引号或去除，避免编译错误
    private static final String SKILL_SYSTEM_PROMPT = """
            你是公众号【幕尽】专属影视选题探测助理。
            账号定位：深度人性向影评公众号；核心目标最大化利用微信搜一搜、看一看免费公域流量；拒绝纯剧情复述，主打人性、欲望、家庭、遗憾、普通人困境。
            
            ## 筛选打分规则，每项0或者1分
            1.【搜索基础】1=作品有广泛群众基础，豆瓣标记人数高，外部平台热度上涨；0=热度低迷、受众极小
            2.【现实共鸣】1=可以引申普通人现实情绪：原生家庭、欲望、遗憾、亲密关系、普通人困境；0=仅适合影迷赏析镜头/打斗美学，缺少现实投射
            3.【差异化角度】1=可以提出区别于网上主流观后感的核心观点，拒绝剧情复述；0=只能复述剧情，很难找到新颖解读
            4.【社交传播潜力】1=可以产出可截图金句、能够引发读者讨论；0=看完很难产生转发、收藏意愿
            
            总分计算：4项相加
            总分≥3 → A池｜第一梯队（优先写，冲搜一搜&看一看流量）
            总分≤2 → B池｜粉丝向调剂选题，不优先冲流量
            
            ## 微信指数趋势预判
            注意：你无法访问微信指数真实接口，只能基于全网舆情、影片上映/二创翻红情况做推演预判，最终必须由人工打开微信指数小程序核验真实数据。
            枚举值三选一：
            -上升：近期全网讨论度走高
            -平稳：经典高分老片，长期稳定有搜索
            -下降：热度已经褪去，尽量避开
            
            ## 硬性约束
            1. 优先挑选A池，优先选择预判趋势为【上升 / 平稳】；预判【下降】尽量不作为首选。
            2. 禁止恐怖、惊悚、鬼怪类题材。
            3. 不要编造不存在的电影，片名必须真实公映。
            4. 输出JSON数组，每一条字段：
            {filmName:影片中文名字,pool:A池或B池,wechatIndexTrend:上升或平稳或下降,coreIdea:简要核心写作切入点,scoreTotal:总分0到4}
            5. 重要限制：输出仅为AI推演初筛候选，后续必须人工打开微信指数小程序复核热度。
            6. 【反同质化】coreIdea必须是具体、细分、反常识的切入点，禁止人性的复杂、爱的力量等万能表述。
            """;

    private static final String MAIN_REVIEW_PROMPT_TPL =
            "【硬性强制规则，必须全部遵守，违反直接作废本次输出】\n" +
                    "角色：资深公众号爆款影评撰稿人。面向普通公众号读者，拒绝晦涩学院派话术。\n" +
                    "写作底层逻辑：电影只是载体，输出人性、现实痛点、情绪共鸣，提升文章收藏、转发数据，拒绝纯剧情流水账复述。\n" +
                    "\n" +
                    "🔴【最高优先级·防幻觉与视角伪装铁律】\n" +
                    "1. 所有剧情、人物、细节只能基于下方提供的【影片核心事实参考】。\n" +
                    "2. ⚠️视角伪装：你必须完全代入刚看完这部电影的资深影迷视角！将下方参考信息内化为你的观影记忆。\n" +
                    "3. 🚫绝对禁止在正文中出现简介、简介里、简介中、剧情简介、官方设定、素材等暴露数据来源的词汇！\n" +
                    "影片核心事实参考：\n\"%s\"\n" +
                    "严格区分：影片客观事实 / 个人主观观点。禁止虚构导演创作意图。\n" +
                    "\n" +
                    "📋【完整工作流程】\n" +
                    "Step1 提炼一句明确的、反常识的中心论点（禁止万能主题）。\n" +
                    "Step2 产出3条公众号爆款标题，覆盖共鸣式、反差冲突式、提问钩子式。标题必须包含具体信息点，禁止空洞悬念。\n" +
                    "Step3 设计开头钩子：100字以内，从一个具体场景/细节/问题切入，禁止宏大开场。\n" +
                    "Step4 正文四段式骨架：\n" +
                    "①开篇入题抛出中心观点\n" +
                    "②精简剧情铺垫控制200字以内\n" +
                    "③主体解读（占全文60%%篇幅），拆分3‑4个解读角度；每一个观点绑定影片真实细节+一个具体现实案例；结尾落地普通人现实感悟\n" +
                    "④结尾升华，输出可摘抄金句；结尾使用一句有力的反问句引发读者内心思考\n" +
                    "Step5 去AI味润色：避免机械排比；全文至少包含2处反问句；拒绝AI套话；加入至少一处个人不确定性表达。\n" +
                    "Step6 公众号排版约束：每段不宜过长；必须将中心论点、核心金句使用 **加粗** 语法高亮。\n" +
                    "\n" +
                    "🚫合规铁律：严禁出现评论区聊聊、欢迎留言等引导互动套话。\n" +
                    "\n" +
                    "✅【输出JSON强制格式】\n" +
                    "{\"centralArgument\":\"一句话中心论点\",\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"完整公众号markdown正文\"}\n" +
                    "\n" +
                    "为电影《%s》撰写公众号影评，风格标签【%s】。\n" +
                    "本次社会语境锚点：%s\n" +
                    "【硬性字数】正文汉字严格1800‑2500。⚠️确保JSON完整闭合！";

    private static final String FALLBACK_REVIEW_PROMPT_TPL =
            "【硬性强制规则】\n" +
                    "角色：公众号影评撰稿人。\n" +
                    "🔴最高约束：所有剧情细节只能基于下方【影片核心事实参考】，严禁编造。代入看过全片的影迷视角。禁止出现简介等暴露数据来源词汇。\n" +
                    "影片核心事实参考：\n\"%s\"\n" +
                    "写作逻辑：少复述剧情，多输出人性感悟现实共鸣；全文至少2个反问；结尾反问引发思考；加入个人不确定性表达。\n" +
                    "规范：1.一句反常识中心论点+3条含具体信息点的钩子标题；2.开篇抓情绪，剧情铺垫≤150字；3.主体3‑4个解读角度，每个绑定具体现实案例；4.结尾金句+反问；5.核心句**加粗**；6.字数1800‑2500；7.禁止引导评论区互动。\n" +
                    "✅输出JSON：{\"centralArgument\":\"中心论点\",\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"正文markdown\"}\n" +
                    "电影《%s》，风格标签【%s】。社会语境锚点：%s。⚠️确保JSON完整闭合！";

    private static final String EXPAND_REVIEW_PROMPT_TPL =
            "【最高优先级指令：素材薄弱时的扩写铁律】\n" +
                    "当前影片简介仅%d字，信息极度稀缺。你必须通过【现实延伸】而非【编造剧情】来达成1800-2500字。\n" +
                    "\n" +
                    "🔴绝对红线：\n" +
                    "1. 电影情节、人物关系、台词细节→只能使用下方【影片核心事实参考】中明确存在的内容，一个字都不能编造。\n" +
                    "2. 允许且必须大幅扩展的部分→现实社会观察、普通人生活类比、人性思辨、同类处境对照、其他电影横向对比、社会现象分析。\n" +
                    "3. 扩写比例要求：电影事实占比≤30%%，现实延伸占比≥70%%。\n" +
                    "\n" +
                    "📋扩写专用四段式结构（严格执行）：\n" +
                    "①开篇（200字）：从一个具体的【当代人普遍困境/情绪痛点】切入，自然引出本片作为案例。不要从电影开场写起。\n" +
                    "②事实锚点（300字）：仅用参考素材中的关键情节作为论证支点，高度压缩，绝不展开复述。\n" +
                    "③现实深潜（1000-1400字）：这是全文主体！围绕中心论点，拆出3-4个现实维度逐一展开。每个维度必须包含：\n" +
                    "   · 一个具体的社会现象/新闻案例/身边人故事（非电影内容，必须有具体名称/数据/地点）\n" +
                    "   · 与电影事实锚点的对照分析\n" +
                    "   · 对普通人生活的具体启示或反思\n" +
                    "   · 至少一处反问句引发读者自省\n" +
                    "④结尾（200字）：金句收束+有力反问。不回扣剧情，只回扣现实。\n" +
                    "\n" +
                    "⚠️视角伪装：你必须代入看过全片的资深影迷视角，将参考信息内化为观影记忆。禁止出现简介、素材等词。\n" +
                    "🚫禁止引导评论区互动。核心句**加粗**。加入至少一处个人不确定性表达。\n" +
                    "\n" +
                    "✅仅输出JSON：{\"centralArgument\":\"一句话中心论点\",\"titles\":[\"标题1\",\"标题2\",\"标题3\"],\"article\":\"markdown正文\"}\n" +
                    "\n" +
                    "影片核心事实参考：\n\"%s\"\n" +
                    "电影《%s》，风格标签【%s】。社会语境锚点：%s。\n" +
                    "【硬性字数】正文汉字严格1800‑2500。⚠️确保JSON完整闭合！";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static String currentFilmTag = "";
    private static TmdbMovieInfo currentTmdbMovieInfo = null;
    private static String currentPredictWechatTrend = "";

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

    public static class SkillFilmCandidate {
        public String filmName;
        public String pool;
        public String wechatIndexTrend;
        public String coreIdea;
        public Integer scoreTotal;
    }

    private static int getEnvInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return defaultValue; }
    }

    private static String normalizeMovieName(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\s\\p{Punct}《》【】()（）·・:：]", "").toLowerCase().trim();
    }

    private static boolean isMovieUsed(String name, List<String> usedList) {
        if (name == null || name.isBlank()) return true;
        String normalized = normalizeMovieName(name);
        for (String used : usedList) {
            if (normalizeMovieName(used).equals(normalized)) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        List<String> usedMovies = new ArrayList<>();
        String pickedMovie = null;
        try {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("🚀 影评生成任务启动");
            System.out.println("=".repeat(60));
            checkEnv();
            usedMovies = loadUsedFromGist();
            System.out.println("📊 已处理电影数量：" + usedMovies.size());
            ReviewResult reviewResult = null;
            for (int attempt = 0; attempt < PICK_MAX_RETRY; attempt++) {
                currentTmdbMovieInfo = null;
                currentFilmTag = "";
                currentPredictWechatTrend = "";
                pickedMovie = pickOneMovie(usedMovies);
                System.out.printf("\n🎯 最终选中：《%s》｜标签【%s】｜微信趋势【%s】\n", pickedMovie, currentFilmTag, currentPredictWechatTrend);
                if (currentTmdbMovieInfo != null) {
                    System.out.printf("📖 TMDB: id=%d, 评分=%.2f, 简介=%d字\n",
                            currentTmdbMovieInfo.id, currentTmdbMovieInfo.voteAverage,
                            currentTmdbMovieInfo.overview != null ? currentTmdbMovieInfo.overview.length() : 0);
                }
                try {
                    reviewResult = generateReview(pickedMovie, currentTmdbMovieInfo != null ? currentTmdbMovieInfo.overview : null);
                    break;
                } catch (MovieCannotHandleException e) {
                    System.err.printf("❌ 《%s》生成失败: %s\n", pickedMovie, e.getMessage());
                    if (!isMovieUsed(pickedMovie, usedMovies)) usedMovies.add(pickedMovie);
                }
            }
            if (reviewResult == null) throw new Exception("多次选片仍无法产出合格影评");
            int len = reviewResult.article.length();
            System.out.println("\n✅ 生成成功！长度=" + len);
            System.out.println("💡 " + reviewResult.centralArgument);
            sendFeishuCard(pickedMovie, reviewResult, len);
            if (!isMovieUsed(pickedMovie, usedMovies)) usedMovies.add(pickedMovie);
            System.out.println("🎉 任务结束");
        } catch (Exception e) {
            System.err.println("\n💥 异常：" + e.getMessage());
            e.printStackTrace();
            if (pickedMovie != null && !isMovieUsed(pickedMovie, usedMovies)) {
                usedMovies.add(pickedMovie);
                System.out.println("ℹ️ 失败影片已加入去重列表: " + pickedMovie);
            }
        } finally {
            if (!usedMovies.isEmpty()) {
                try { saveUsedToGist(usedMovies); } catch (Exception e) {
                    System.err.println("⚠️ Gist保存失败: " + e.getMessage());
                }
            }
        }
    }

    private static void checkEnv() throws Exception {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isBlank()) throw new Exception("DEEPSEEK_API_KEY 未配置");
        if (FEISHU_WEBHOOK == null || FEISHU_WEBHOOK.isBlank()) throw new Exception("FEISHU_WEBHOOK 未配置");
        if (GIST_ID == null || GITHUB_PAT == null || GIST_ID.isBlank() || GITHUB_PAT.isBlank()) throw new Exception("GIST_ID/GH_PAT_GIST 未配置");
    }

    private static String pickOneMovie(List<String> used) throws Exception {
        for (int i = 0; i < PICK_MAX_RETRY; i++) {
            System.out.printf("\n🔄 [选片] 第%d/%d次\n", i + 1, PICK_MAX_RETRY);
            currentFilmTag = FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
            List<SkillFilmCandidate> skillCandidates = aiGetClassicMovieNamesByTag(currentFilmTag);
            for (SkillFilmCandidate cand : skillCandidates) {
                if ("下降".equals(cand.wechatIndexTrend)) continue;
                if (isBlackMovie(cand.filmName) || isMovieUsed(cand.filmName, used)) continue;
                TmdbMovieInfo info = tmdbSearchMovie(cand.filmName);
                if (info == null) continue;
                boolean isHorror = info.genres != null && info.genres.stream().anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                if (isHorror) continue;
                if (isValidTmdbMovie(info)) {
                    currentTmdbMovieInfo = info;
                    currentFilmTag = autoMapFilmTagByGenres(info);
                    currentPredictWechatTrend = cand.wechatIndexTrend;
                    return info.title;
                }
            }
            if (TMDB_API_KEY != null && !TMDB_API_KEY.isBlank()) {
                List<TmdbMovieInfo> candidates = fetchTmdbCandidates();
                List<TmdbMovieInfo> filtered = new ArrayList<>();
                for (TmdbMovieInfo info : candidates) {
                    String t = info.title != null ? info.title : info.originalTitle;
                    boolean horror = info.genres != null && info.genres.stream().anyMatch(g -> g.id == GENRE_HORROR || g.id == GENRE_THRILLER);
                    if (!isBlackMovie(t) && !isMovieUsed(t, used) && !horror) filtered.add(info);
                }
                if (!filtered.isEmpty()) {
                    SkillFilmCandidate sel = aiSelectBestFilm(filtered);
                    if (sel != null) {
                        for (TmdbMovieInfo info : filtered) {
                            if (info.title.equals(sel.filmName) || (info.originalTitle != null && info.originalTitle.equals(sel.filmName))) {
                                currentTmdbMovieInfo = info;
                                currentFilmTag = autoMapFilmTagByGenres(info);
                                currentPredictWechatTrend = sel.wechatIndexTrend;
                                return info.title;
                            }
                        }
                    }
                }
            }
            List<SkillFilmCandidate> aiPool = aiGenerateTaggedMoviePool(used);
            for (SkillFilmCandidate cand : aiPool) {
                if (!isBlackMovie(cand.filmName) && !isMovieUsed(cand.filmName, used)) {
                    currentTmdbMovieInfo = null;
                    currentPredictWechatTrend = cand.wechatIndexTrend;
                    return cand.filmName;
                }
            }
        }
        throw new Exception("选片失败");
    }

    private static List<TmdbMovieInfo> fetchTmdbCandidates() {
        List<Long> idList = new ArrayList<>();
        String[] endpoints = {"/trending/movie/week", "/movie/now_playing", "/movie/popular"};
        for (String ep : endpoints) {
            try {
                HttpUrl url = HttpUrl.parse(TMDB_BASE + ep).newBuilder()
                        .addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").build();
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                        if (results != null) for (Object o : results) {
                            JSONObject obj = (JSONObject) o;
                            long mid = obj.getLongValue("id");
                            if (obj.getDoubleValue("vote_average") >= TMDB_MIN_VOTE && !idList.contains(mid)) idList.add(mid);
                        }
                    }
                }
            } catch (Exception e) { System.err.println("  ❌ " + ep + ": " + e.getMessage()); }
            if (idList.size() >= 15) break;
        }
        List<TmdbMovieInfo> valid = new ArrayList<>();
        for (long mid : idList) {
            TmdbMovieInfo info = tmdbGetMovieDetail(mid);
            if (isValidTmdbMovie(info)) valid.add(info);
        }
        valid.sort(Comparator.comparingInt((TmdbMovieInfo m) -> m.overview == null ? 0 : m.overview.length()).reversed());
        return valid;
    }

    private static SkillFilmCandidate aiSelectBestFilm(List<TmdbMovieInfo> candidates) throws IOException {
        JSONArray arr = new JSONArray();
        for (TmdbMovieInfo info : candidates) {
            JSONObject item = new JSONObject();
            item.put("title", info.title);
            item.put("vote_average", info.voteAverage);
            item.put("overview", info.overview);
            arr.add(item);
        }
        String prompt = "从以下候选中选最适合公众号深度人性影评的电影，输出JSON数组（取1条）：\n" + arr;
        String resp = extractJsonSafely(callDeepSeek(SKILL_SYSTEM_PROMPT, prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL));
        try {
            JSONArray ja = parseJsonArrayLoose(resp);
            if (ja.isEmpty()) return null;
            JSONObject jo = ja.getJSONObject(0);
            SkillFilmCandidate c = new SkillFilmCandidate();
            c.filmName = jo.getString("filmName");
            c.pool = jo.getString("pool");
            c.wechatIndexTrend = jo.getString("wechatIndexTrend");
            c.coreIdea = jo.getString("coreIdea");
            c.scoreTotal = jo.getInteger("scoreTotal");
            return c;
        } catch (Exception e) { return null; }
    }

    private static String autoMapFilmTagByGenres(TmdbMovieInfo info) {
        if (info == null || info.genres == null || info.genres.isEmpty())
            return FILM_TAGS[ThreadLocalRandom.current().nextInt(FILM_TAGS.length)];
        List<Integer> ids = new ArrayList<>();
        info.genres.forEach(g -> ids.add(g.id));
        if (ids.contains(18) && ids.contains(80)) return "社会讽刺、现实隐喻";
        if (ids.contains(18) || ids.contains(9648)) return "现实扎心、人间百态";
        if (ids.contains(80)) return "人性深度、善恶博弈";
        if (ids.contains(10749)) return "青春成长、遗憾治愈";
        if (ids.contains(10751)) return "亲情羁绊、烟火人间";
        if (ids.contains(35)) return "温情治愈、治愈内耗";
        List<String> fb = List.of("人性深度、自我救赎", "底层生活、人间真实", "平凡人性、微光治愈", "人生百态、世事通透");
        return fb.get(ThreadLocalRandom.current().nextInt(fb.size()));
    }

    private static List<SkillFilmCandidate> aiGetClassicMovieNamesByTag(String tag) throws IOException {
        String prompt = "根据风格标签【" + tag + "】输出JSON数组（5-8条），每条含filmName/pool/wechatIndexTrend/coreIdea/scoreTotal。真实高分电影，禁恐怖惊悚。coreIdea必须是具体细分切口，禁止万能表述。";
        String resp = extractJsonSafely(callDeepSeek(SKILL_SYSTEM_PROMPT, prompt, MAX_TOKENS_NORMAL, TEMPERATURE_NORMAL));
        List<SkillFilmCandidate> list = new ArrayList<>();
        try {
            JSONArray arr = parseJsonArrayLoose(resp);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject jo = arr.getJSONObject(i);
                SkillFilmCandidate c = new SkillFilmCandidate();
                c.filmName = jo.getString("filmName");
                c.pool = jo.getString("pool");
                c.wechatIndexTrend = jo.getString("wechatIndexTrend");
                c.coreIdea = jo.getString("coreIdea");
                c.scoreTotal = jo.getInteger("scoreTotal");
                list.add(c);
            }
        } catch (Exception e) { System.err.println("  ⚠️ Skill获取失败:" + e.getMessage()); }
        return list;
    }

    private static List<SkillFilmCandidate> aiGenerateTaggedMoviePool(List<String> usedMovies) throws IOException {
        String usedHint = "";
        if (!usedMovies.isEmpty()) {
            int from = Math.max(0, usedMovies.size() - 50);
            List<String> recent = usedMovies.subList(from, usedMovies.size());
            usedHint = "\n⚠️以下电影已写过，严禁重复推荐：" + String.join("、", recent);
        }
        String prompt = "根据标签【" + currentFilmTag + "】输出JSON数组（5条），含filmName/pool/wechatIndexTrend/coreIdea/scoreTotal。真实高分，禁恐怖。coreIdea必须是具体细分切口。" + usedHint;
        String resp = extractJsonSafely(callDeepSeek(SKILL_SYSTEM_PROMPT, prompt, MAX_TOKENS_NORMAL, TEMPERATURE_EXPAND));
        List<SkillFilmCandidate> list = new ArrayList<>();
        try {
            JSONArray arr = parseJsonArrayLoose(resp);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject jo = arr.getJSONObject(i);
                SkillFilmCandidate c = new SkillFilmCandidate();
                c.filmName = jo.getString("filmName");
                c.pool = jo.getString("pool");
                c.wechatIndexTrend = jo.getString("wechatIndexTrend");
                c.coreIdea = jo.getString("coreIdea");
                c.scoreTotal = jo.getInteger("scoreTotal");
                list.add(c);
            }
        } catch (Exception e) { System.err.println("  ⚠️ 兜底池生成失败:" + e.getMessage()); }
        return list;
    }

    private static TmdbMovieInfo tmdbSearchMovie(String name) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/search/movie").newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").addQueryParameter("query", name).build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONArray results = JSON.parseObject(resp.body().string()).getJSONArray("results");
                if (results == null || results.isEmpty()) return null;
                return tmdbGetMovieDetail(results.getJSONObject(0).getLongValue("id"));
            }
        } catch (Exception e) { return null; }
    }

    private static TmdbMovieInfo tmdbGetMovieDetail(long movieId) {
        try {
            HttpUrl url = HttpUrl.parse(TMDB_BASE + "/movie/" + movieId).newBuilder()
                    .addQueryParameter("api_key", TMDB_API_KEY).addQueryParameter("language", "zh-CN").build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONObject jo = JSON.parseObject(resp.body().string());
                TmdbMovieInfo info = new TmdbMovieInfo();
                info.id = jo.getLongValue("id");
                info.title = jo.getString("title");
                info.originalTitle = jo.getString("original_title");
                info.overview = jo.getString("overview");
                info.voteAverage = jo.getDoubleValue("vote_average");
                JSONArray ga = jo.getJSONArray("genres");
                List<TmdbGenre> gl = new ArrayList<>();
                if (ga != null) for (Object o : ga) {
                    JSONObject gjo = (JSONObject) o;
                    TmdbGenre g = new TmdbGenre();
                    g.id = gjo.getIntValue("id");
                    g.name = gjo.getString("name");
                    gl.add(g);
                }
                info.genres = gl;
                return info;
            }
        } catch (Exception e) { return null; }
    }

    private static boolean isValidTmdbMovie(TmdbMovieInfo info) {
        if (info == null) return false;
        if (info.overview == null || info.overview.length() < 80) return false;
        if ((info.title == null || info.title.isBlank()) && (info.originalTitle == null || info.originalTitle.isBlank())) return false;
        return info.voteAverage >= TMDB_MIN_VOTE;
    }

    private static boolean isBlackMovie(String name) {
        if (name == null || name.isBlank()) return true;
        String clean = name.replaceAll("\\s+", "");
        for (String kw : MOVIE_BLACKLIST_KEYWORD) if (clean.contains(kw)) return true;
        return false;
    }

    private static String pickSocialContext() {
        return SOCIAL_CONTEXT_POOL[ThreadLocalRandom.current().nextInt(SOCIAL_CONTEXT_POOL.length)];
    }

    private static ReviewResult generateReview(String movieName, String tmdbOverview) throws Exception {
        int emptyCount = 0;
        String safeOverview = (tmdbOverview == null || tmdbOverview.isBlank()) ? "" : tmdbOverview.replace("\u201c", "\"").replace("\u201d", "\"");
        boolean weakOverview = safeOverview.length() < OVERVIEW_WEAK_THRESHOLD;
        System.out.printf("✍️ 简介%d字，薄弱=%b\n", safeOverview.length(), weakOverview);

        for (int i = 0; i < ARTICLE_MAX_RETRY; i++) {
            System.out.printf("🔄 生成第%d/%d轮\n", i + 1, ARTICLE_MAX_RETRY);
            String randomAngle = WRITING_ANGLES[ThreadLocalRandom.current().nextInt(WRITING_ANGLES.length)];
            String socialContext = pickSocialContext();
            String systemPrompt = "你是一个资深影评人。" + randomAngle + "\n\n" + ANTI_HOMOGENEITY_INJECTION;

            String prompt;
            int maxToken;
            double temp;
            if (weakOverview) {
                prompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview.length(), safeOverview, movieName, currentFilmTag, socialContext);
                maxToken = MAX_TOKENS_EXPAND;
                temp = TEMPERATURE_EXPAND;
            } else {
                prompt = i < 2
                        ? String.format(MAIN_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag, socialContext)
                        : String.format(FALLBACK_REVIEW_PROMPT_TPL, safeOverview, movieName, currentFilmTag, socialContext);
                maxToken = MAX_TOKENS_NORMAL;
                temp = TEMPERATURE_NORMAL;
            }

            String contentRaw;
            try {
                contentRaw = callDeepSeek(systemPrompt, prompt, maxToken, temp);
            } catch (IOException ex) {
                System.err.printf("  ❌ 网络异常: %s\n", ex.getMessage());
                sleepRandom(1200, 2500);
                continue;
            }
            contentRaw = extractJsonSafely(contentRaw);
            if (contentRaw.isBlank()) {
                if (++emptyCount >= 3) throw new MovieCannotHandleException("连续空返回");
                sleepRandom(1200, 2500);
                continue;
            }
            JSONObject jo;
            try { jo = parseJsonLoose(contentRaw); } catch (Exception e) {
                jo = extractJsonByRegex(contentRaw);
                if (jo == null) { sleepRandom(1200, 2500); continue; }
            }
            String article = jo.getString("article");
            JSONArray titleArr = jo.getJSONArray("titles");
            String centralArg = jo.getString("centralArgument");
            if (article == null || titleArr == null || titleArr.size() != 3 || centralArg == null) {
                sleepRandom(1200, 2500); continue;
            }
            article = cleanAiArticle(article);
            article = removeInteractionCTA(article);
            article = deduplicateArticle(article);
            int len = article.length();
            System.out.printf("  📏 长度:%d | 目标:[%d~%d]\n", len, ARTICLE_TARGET_MIN, ARTICLE_TARGET_MAX);
            if (len >= ARTICLE_TARGET_MIN && len <= ARTICLE_TARGET_MAX) {
                ReviewResult r = new ReviewResult();
                r.centralArgument = centralArg;
                r.titles = titleArr.toList(String.class);
                r.article = article;
                return r;
            }
            sleepRandom(1200, 2500);
        }

        System.out.println("🚨 保底扩写...");
        String finalAngle = WRITING_ANGLES[ThreadLocalRandom.current().nextInt(WRITING_ANGLES.length)];
        String finalSocialContext = pickSocialContext();
        String finalSysPrompt = "你是一个资深影评人。" + finalAngle + "\n\n" + ANTI_HOMOGENEITY_INJECTION;
        String finalPrompt = String.format(EXPAND_REVIEW_PROMPT_TPL, safeOverview.length(), safeOverview, movieName, currentFilmTag, finalSocialContext);
        String finalRaw = extractJsonSafely(callDeepSeek(finalSysPrompt, finalPrompt, MAX_TOKENS_EXPAND, TEMPERATURE_EXPAND));
        if (finalRaw.isBlank()) throw new MovieCannotHandleException("保底扩写返回空");
        JSONObject fjo;
        try { fjo = parseJsonLoose(finalRaw); } catch (Exception e) {
            fjo = extractJsonByRegex(finalRaw);
            if (fjo == null) throw new MovieCannotHandleException("保底JSON解析失败");
        }
        ReviewResult res = new ReviewResult();
        res.centralArgument = fjo.getString("centralArgument");
        JSONArray ft = fjo.getJSONArray("titles");
        res.titles = ft != null ? ft.toList(String.class) : List.of(movieName, movieName + "解读", movieName + "影评");
        res.article = deduplicateArticle(removeInteractionCTA(cleanAiArticle(fjo.getString("article"))));
        if (res.article == null) res.article = "";
        if (res.article.length() < ARTICLE_TARGET_MIN) {
            StringBuilder sb = new StringBuilder(res.article);
            while (sb.length() < ARTICLE_TARGET_MIN)
                sb.append("\n\n很多时候，电影里看见的是别人的故事，映照的却是我们自己一路走来的人生境遇。那些遗憾、挣扎与和解，不止发生在银幕之上，也藏在每一个普通人日复一日的生活之中。");
            res.article = sb.toString();
        }
        if (res.article.length() > ARTICLE_TARGET_MAX) res.article = res.article.substring(0, ARTICLE_TARGET_MAX);
        return res;
    }

    private static String deduplicateArticle(String article) {
        if (article == null || article.isBlank()) return article;
        article = article.replaceAll("(?m)^\\s*这不仅仅是一部电影[，,].*$", "");
        article = article.replaceAll("(?m)^\\s*或许[，,]这就是[人生|生活|成长|爱情]的[真谛|本质|真相][。].*$", "");
        article = article.replaceAll("(?m)^\\s*当我们走出影院[，,].*$", "");
        article = article.replaceAll("(?m)^\\s*回到现实[中来]?[，,].*$", "");
        article = article.replaceAll("(?m)^\\s*在这个[喧嚣|浮躁|快节奏]的[时代|社会|世界][里中]?[，,].*$", "");
        article = article.replaceAll("\\n{3,}", "\n\n");
        return article.trim();
    }

    private static void sendFeishuCard(String movieName, ReviewResult result, int articleLength) {
        try {
            JSONObject card = new JSONObject();
            card.put("msg_type", "interactive");
            JSONObject cc = new JSONObject();
            JSONObject header = new JSONObject();
            JSONObject ht = new JSONObject();
            ht.put("tag", "plain_text");
            ht.put("content", "🎬 影评 | 《" + movieName + "》");
            header.put("title", ht);
            header.put("template", "blue");
            cc.put("header", header);
            JSONArray elements = new JSONArray();

            JSONObject infoDiv = new JSONObject();
            infoDiv.put("tag", "div");
            JSONObject it = new JSONObject();
            it.put("tag", "lark_md");
            it.put("content", "**🏷️标签：**" + currentFilmTag + "\n**📡微信趋势：**" + currentPredictWechatTrend +
                    "\n**💡论点：**" + result.centralArgument + "\n**📏长度：**" + articleLength);
            infoDiv.put("text", it);
            elements.add(infoDiv);
            elements.add(buildDivider());

            JSONObject td = new JSONObject();
            td.put("tag", "div");
            JSONObject tt = new JSONObject();
            tt.put("tag", "lark_md");
            StringBuilder tsb = new StringBuilder("**📌标题：**\n");
            if (result.titles != null) for (int i = 0; i < result.titles.size(); i++)
                tsb.append(i + 1).append(". ").append(result.titles.get(i)).append("\n");
            tt.put("content", tsb.toString().trim());
            td.put("text", tt);
            elements.add(td);
            elements.add(buildDivider());

            JSONObject ad = new JSONObject();
            ad.put("tag", "div");
            JSONObject at = new JSONObject();
            at.put("tag", "lark_md");
            at.put("content", result.article != null ? result.article : "（空）");
            ad.put("text", at);
            elements.add(ad);

            cc.put("elements", elements);
            card.put("card", cc);
            RequestBody rb = RequestBody.create(card.toJSONString(), MediaType.get("application/json; charset=utf-8"));
            Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(rb).build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                System.out.println(resp.isSuccessful() ? "✅ 飞书发送成功" : "❌ 飞书失败:" + resp.code());
            }
        } catch (Exception e) { System.err.println("❌ 飞书异常:" + e.getMessage()); }
    }

    private static JSONObject buildDivider() {
        JSONObject d = new JSONObject();
        d.put("tag", "hr");
        return d;
    }

    private static List<String> loadUsedFromGist() {
        Request req = new Request.Builder().url("https://api.github.com/gists/" + GIST_ID)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .addHeader("Accept", "application/vnd.github.v3+json").get().build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("⚠️ Gist读取失败 HTTP " + resp.code() + "，本次去重可能失效！");
                return new ArrayList<>();
            }
            if (resp.body() == null) {
                System.err.println("⚠️ Gist响应体为空，本次去重可能失效！");
                return new ArrayList<>();
            }
            JSONObject gj = JSON.parseObject(resp.body().string());
            JSONObject files = gj.getJSONObject("files");
            if (files == null || !files.containsKey(GIST_FILENAME)) {
                System.out.println("ℹ️ Gist中尚无历史记录文件，首次运行");
                return new ArrayList<>();
            }
            String c = files.getJSONObject(GIST_FILENAME).getString("content");
            List<String> list = (c == null || c.isBlank()) ? new ArrayList<>() : JSON.parseArray(c).toList(String.class);
            System.out.println("✅ Gist读取成功，已用片库=" + list.size() + "部");
            return list;
        } catch (Exception e) {
            System.err.println("⚠️ Gist读取异常: " + e.getMessage() + "，本次去重可能失效！");
            return new ArrayList<>();
        }
    }

    private static void saveUsedToGist(List<String> used) throws IOException {
        JSONObject fo = new JSONObject();
        fo.put("content", new JSONArray(used).toJSONString());
        JSONObject fos = new JSONObject();
        fos.put(GIST_FILENAME, fo);
        JSONObject body = new JSONObject();
        body.put("files", fos);
        RequestBody rb = RequestBody.create(body.toJSONString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder().url("https://api.github.com/gists/" + GIST_ID)
                .addHeader("Authorization", "token " + GITHUB_PAT)
                .addHeader("Accept", "application/vnd.github.v3+json").patch(rb).build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            System.out.println(resp.isSuccessful() ? "✅ Gist保存成功(" + used.size() + ")" : "❌ Gist失败:" + resp.code());
        }
    }

    private static String callDeepSeek(String systemPrompt, String userPrompt, int maxTokens, double temperature) throws IOException {
        IOException lastEx = null;
        for (int r = 0; r <= DEEPSEEK_NET_RETRY; r++) {
            JSONObject body = new JSONObject();
            body.put("model", DEEPSEEK_MODEL);
            body.put("max_tokens", Math.min(maxTokens, DEEPSEEK_MAX_OUTPUT_TOKENS));
            body.put("temperature", temperature);
            body.put("response_format", JSONObject.of("type", "json_object"));
            JSONArray msgs = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isBlank())
                msgs.add(JSONObject.of("role", "system", "content", systemPrompt));
            msgs.add(JSONObject.of("role", "user", "content", userPrompt));
            body.put("messages", msgs);
            RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
            Request req = new Request.Builder().url(DEEPSEEK_URL)
                    .addHeader("Authorization", "Bearer " + DEEPSEEK_API_KEY).post(rb).build();
            try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("空响应体");
                String raw = resp.body().string();
                if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
                JSONObject jo = JSON.parseObject(raw);
                JSONArray choices = jo.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) throw new IOException("choices为空");
                JSONObject c0 = choices.getJSONObject(0);
                JSONObject msg = c0.getJSONObject("message");
                String content = msg != null ? msg.getString("content") : null;
                if ("length".equals(c0.getString("finish_reason"))) {
                    System.err.println("  ⚠️ max_tokens截断");
                    return content != null ? content.trim() : "";
                }
                if (content == null || content.isBlank()) {
                    System.err.println("  ⚠️ content为空");
                    return "";
                }
                return content.trim();
            } catch (IOException e) {
                lastEx = e;
                System.err.printf("  ❌ retry=%d err=%s\n", r, e.getMessage());
            }
        }
        throw new IOException("重试耗尽", lastEx);
    }

    private static String extractJsonSafely(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = stripCodeBlock(raw).trim();
        int startIdx = -1;
        char openChar = 0, closeChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') { openChar = c; closeChar = c == '{' ? '}' : ']'; startIdx = i; break; }
        }
        if (startIdx == -1) return "";
        int depth = 0; boolean inStr = false, esc = false; int endIdx = -1;
        for (int i = startIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == openChar) depth++;
            else if (c == closeChar) { depth--; if (depth == 0) { endIdx = i; break; } }
        }
        if (endIdx == -1) {
            String tr = s.substring(startIdx);
            int ob = 0, oq = 0; boolean cs = false, ce = false;
            for (int i = 0; i < tr.length(); i++) {
                char c = tr.charAt(i);
                if (ce) { ce = false; continue; }
                if (c == '\\') { ce = true; continue; }
                if (c == '"') { cs = !cs; continue; }
                if (cs) continue;
                if (c == '{') ob++; else if (c == '}') ob--;
                else if (c == '[') oq++; else if (c == ']') oq--;
            }
            StringBuilder fix = new StringBuilder(tr);
            if (cs) fix.append("\"");
            while (oq-- > 0) fix.append("]");
            while (ob-- > 0) fix.append("}");
            return fix.toString();
        }
        return s.substring(startIdx, endIdx + 1);
    }

    private static JSONObject extractJsonByRegex(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JSONObject jo = new JSONObject();
            Matcher am = Pattern.compile("\"centralArgument\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL).matcher(text);
            if (am.find()) jo.put("centralArgument", am.group(1).replace("\\\"", "\"").replace("\\n", "\n"));
            Matcher tm = Pattern.compile("\"titles\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(text);
            if (tm.find()) {
                JSONArray ta = new JSONArray();
                Matcher ti = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(tm.group(1));
                while (ti.find()) ta.add(ti.group(1).replace("\\\"", "\"").replace("\\n", "\n"));
                jo.put("titles", ta);
            }
            int as = text.indexOf("\"article\"");
            if (as != -1) {
                int vs = text.indexOf("\"", as + 9);
                int ve = text.lastIndexOf("\"");
                if (vs != -1 && ve > vs)
                    jo.put("article", text.substring(vs + 1, ve).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\"));
            }
            if (jo.containsKey("article") && jo.containsKey("titles") && jo.containsKey("centralArgument")) return jo;
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONObject parseJsonLoose(String s) {
        if (s == null || s.isBlank()) throw new RuntimeException("JSON为空");
        try { return JSON.parseObject(s); } catch (Exception e1) {
            try { return JSON.parseObject(s, JSONReader.Feature.IgnoreCheckClose); } catch (Exception e2) {
                throw new RuntimeException("JSON解析失败", e2);
            }
        }
    }

    private static JSONArray parseJsonArrayLoose(String s) {
        if (s == null || s.isBlank()) throw new RuntimeException("JSON数组为空");
        try { return JSON.parseArray(s); } catch (Exception ignored) {}
        try {
            JSONObject obj = JSON.parseObject(s, JSONReader.Feature.IgnoreCheckClose);
            if (obj != null) {
                for (Object v : obj.values()) if (v instanceof JSONArray ja && !ja.isEmpty() && ja.get(0) instanceof JSONObject) return ja;
                if (obj.containsKey("filmName") || obj.containsKey("title")) { JSONArray a = new JSONArray(); a.add(obj); return a; }
            }
        } catch (Exception ignored) {}
        try { return JSON.parseArray(s, JSONReader.Feature.IgnoreCheckClose); } catch (Exception e) {
            throw new RuntimeException("JSON数组解析失败", e);
        }
    }

    private static String removeInteractionCTA(String article) {
        if (article == null || article.isBlank()) return article;
        String[] phrases = {"评论区聊聊", "评论区说说", "评论区见", "评论区等你", "评论区留言", "评论区告诉我",
                "欢迎留言", "欢迎在评论区", "欢迎讨论", "欢迎分享", "留言告诉我", "留言说说",
                "说说你的看法", "分享你的看法", "你怎么看？欢迎讨论", "你觉得呢？欢迎留言"};
        String r = article;
        for (String p : phrases) r = r.replace(p, "");
        return r.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String cleanAiArticle(String text) {
        if (text == null) return "";
        text = text.replaceAll("首先，|其次，|最后，|总的来说，|总而言之，|综上所述，", "");
        text = text.replaceAll("这部电影告诉我们|这部影片揭示了", "它揭示了");
        text = text.replaceAll("引人深思|发人深省|值得一看", "");
        text = text.replaceAll("【.*?】", "");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("从简介(里|中|来看|可以看出|得知)", "在影片中");
        text = text.replaceAll("简介(里|中|提到|显示|写道)", "电影中");
        text = text.replaceAll("根据简介", "在故事里");
        text = text.replaceAll("剧情简介", "电影情节");
        text = text.replaceAll("官方简介", "影片");
        text = text.replaceAll("正如简介", "正如影片");
        return text.trim();
    }

    private static void sleepRandom(int min, int max) {
        try { TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(min, max + 1)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String stripCodeBlock(String text) {
        if (text == null) return "";
        String s = text.trim();
        Matcher m = Pattern.compile("^```(?:[a-zA-Z0-9]*)\\s*\\R?(.*?)\\R?\\s*```$", Pattern.DOTALL).matcher(s);
        return m.matches() ? m.group(1).trim() : s.trim();
    }
}
