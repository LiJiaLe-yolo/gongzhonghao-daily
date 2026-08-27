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
        mdSb.append(escapeLarkMd(reviewResult.article));
        final int MAX_CARD_CONTENT = 26000;
        String fullContent = mdSb.toString();
        if (fullContent.length() > MAX_CARD_CONTENT) {
            mdSb.setLength(0);
            mdSb.append(fullContent, 0, MAX_CARD_CONTENT);
            mdSb.append("\n\n⚠️【正文过长，卡片已截断，完整内容存在程序内存】");
        }
        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONArray elements = new JSONArray();
        elements.add(JSONObject.of("tag", "div", "text", JSONObject.of("tag", "lark_md", "content", mdSb.toString())));
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
        try(Response resp = HTTP_CLIENT.newCall(req).execute()){
            if(!resp.isSuccessful()){
                System.err.printf("[sendFeishuCard]飞书推送失败 code=%d%n", resp.code());
            }
        }
    }

    /**
     * 飞书markdown特殊字符转义
     */
    private static String escapeLarkMd(String input){
        if(input == null) return "";
        return input.replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("`", "\\`");
    }

}
