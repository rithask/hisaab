package com.hisaab.mail;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.Set;

/** Converts HTML email bodies to text, keeping block structure as line breaks (tables become one cell per line). */
public final class HtmlText {
    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "br", "tr", "td", "th", "li", "ul", "ol", "table", "tbody", "thead",
            "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "header", "footer", "blockquote", "hr");

    private HtmlText() {}

    public static String toText(String html) {
        if (html == null) return "";
        Document doc = Jsoup.parse(html);
        doc.select("script, style, head").remove();
        StringBuilder sb = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    sb.append(t.text());
                } else if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    sb.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    sb.append('\n');
                }
            }
        });
        return normaliseLines(sb.toString());
    }

    /** Collapses spaces within lines and drops blank lines. */
    public static String normaliseLines(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.replace(' ', ' ').split("\\R")) {
            String l = line.replaceAll("[ \\t]+", " ").trim();
            if (!l.isEmpty()) out.append(l).append('\n');
        }
        return out.toString().trim();
    }
}
