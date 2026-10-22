package org.telegram.messenger;

import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits assistant output from reasoning / chain-of-thought models
 * (Qwen3 {@code <think>}, DeepSeek-style, etc.) into a collapsible
 * «рассуждение» block and the final answer shown to the user.
 */
public final class MglaAiReasoning {

    private static final Pattern[] COMPLETE = {
            Pattern.compile("(?is)<\\s*think\\s*>(.*?)<\\s*/\\s*think\\s*>"),
            Pattern.compile("(?is)<\\s*thinking\\s*>(.*?)<\\s*/\\s*thinking\\s*>"),
            Pattern.compile("(?is)<\\s*reasoning\\s*>(.*?)<\\s*/\\s*reasoning\\s*>"),
            Pattern.compile("(?is)<\\|?redacted_thinking\\|?>(.*?)<\\|?/\\s*redacted_thinking\\|?>"),
    };

    private static final Pattern[] OPEN_ONLY = {
            Pattern.compile("(?is)<\\s*think\\s*>"),
            Pattern.compile("(?is)<\\s*thinking\\s*>"),
            Pattern.compile("(?is)<\\s*reasoning\\s*>"),
            Pattern.compile("(?is)<\\|?redacted_thinking\\|?>"),
    };

    public static final class Parts {
        /** Chain-of-thought text without tags; empty if none. */
        public final String reasoning;
        /** Final answer (markdown-safe); may be empty while still thinking. */
        public final String answer;
        /** True while an opening think tag has no closing tag yet (live stream). */
        public final boolean reasoningOpen;

        public Parts(String reasoning, String answer, boolean reasoningOpen) {
            this.reasoning = reasoning != null ? reasoning : "";
            this.answer = answer != null ? answer : "";
            this.reasoningOpen = reasoningOpen;
        }

        public boolean hasReasoning() {
            return !TextUtils.isEmpty(reasoning) || reasoningOpen;
        }
    }

    private MglaAiReasoning() {
    }

    public static Parts parse(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return new Parts("", "", false);
        }
        StringBuilder reasoning = new StringBuilder();
        String rest = raw;
        boolean matched = false;
        for (Pattern p : COMPLETE) {
            Matcher m = p.matcher(rest);
            if (!m.find()) {
                continue;
            }
            matched = true;
            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) {
                String chunk = m.group(1);
                if (!TextUtils.isEmpty(chunk)) {
                    if (reasoning.length() > 0) {
                        reasoning.append("\n\n");
                    }
                    reasoning.append(chunk.trim());
                }
                m.appendReplacement(sb, "");
            }
            m.appendTail(sb);
            rest = sb.toString();
            break;
        }
        if (!matched) {
            for (Pattern open : OPEN_ONLY) {
                Matcher m = open.matcher(rest);
                if (m.find()) {
                    String before = rest.substring(0, m.start()).trim();
                    String after = rest.substring(m.end());
                    return new Parts(after.trim(), before, true);
                }
            }
            return new Parts("", rest.trim(), false);
        }
        return new Parts(reasoning.toString().trim(), rest.trim(), false);
    }

    /** Remove think/reasoning blocks before feeding history back into the model. */
    public static String stripForContext(String raw) {
        Parts parts = parse(raw);
        if (!parts.hasReasoning()) {
            return raw != null ? raw : "";
        }
        return parts.answer;
    }

    public static boolean looksLikeReasoningModel(String modelId) {
        if (TextUtils.isEmpty(modelId)) {
            return false;
        }
        String id = modelId.toLowerCase(Locale.US);
        return id.contains("qwen3")
                || id.contains("deepseek-r1")
                || id.contains("reasoning")
                || id.contains("think")
                || id.contains("r1-");
    }
}
