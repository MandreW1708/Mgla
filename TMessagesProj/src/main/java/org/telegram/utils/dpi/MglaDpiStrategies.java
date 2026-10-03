package org.telegram.utils.dpi;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Стратегии обхода — командные строки ciadpi (ByeDPI), полностью совместимые с ByeByeDPI:
 * строку из его «Подбора стратегий» можно вставить как есть.
 * <p>
 * Основное: -s разрез, -d disorder, -o/-q OOB, -f фейк (с TTL -t), -r разрез TLS-записи,
 * -n SNI фейка, -Q модификация фейка, -A триггер следующей группы (t — таймаут/сброс,
 * r — редирект, s — ошибка TLS). Позиция: offset[:repeats:skip][+s|+h|+n][e|m].
 */
public final class MglaDpiStrategies {

    public static final String SNI_PLACEHOLDER = "{sni}";
    public static final String DEFAULT_SNI = "google.com";
    public static final String DEFAULT_STRATEGY = "-d1 -s1+s -r1+s -f-1 -t8 -a1";

    /** Встроенный список стратегий подбора (тот же, что в ByeByeDPI). */
    public static final String[] BUILT_IN = {
        "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
        "-n {sni} -Qr -f-204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1",
        "-n {sni} -Qr -f-205 -a1 -As -s1:3+sm -a1 -As -s5:8+sm -a1 -As -d3 -q7 -o2 -f-43 -f-85 -f-165 -r5 -Mh -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1:11+sm -S -a1 -At,r,s -n {sni} -Qr -f1 -d1:11+sm -s1:11+sm -S -a1",
        "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-f1+nme -t6 -a1 -As -n {sni} -Qr -s1:6+sm -a1 -As -s5:12+sm -a1 -As -d3 -q7 -r6 -Mh -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
        "-d1 -d3+s -s6+s -d6+s -s7+s -d8+s -s10+s -a1 -t12 -At,s -r3",
        "-f1 -t5 -n {sni} -q3+h -Qr -f2 -q1 -r1+s -t15 -q1 -o2 -a1",
        "-n {sni} -d2:5:2+h -f-3 -r2+sm -o2 -o50+s -r2+s -f-4 -a1",
        "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
        "-r-1+s -o20+sm -s3:7+sm -d5:3+sm -f300+s -Qr -f-1 -a1",
        "-o2 -O4 -s1 -q1 -a1 -Ar -s5 -o1+s -f1+s -r20+s -a1",
        "-o1 -r-5+se -a1 -At,r,s -d1 -n {sni} -Qr -f-1 -a1",
        "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "-n {sni} -Qr -f6+nr -d2 -d11 -f9+hm -o3 -t7 -a1",
        "-r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
        "-d9+s -q20+s -s25+s -t5 -a1 -At,r,s -r1+h -a1",
        "-q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -S -a1",
        "-d1 -s1+s -r1+s -e1 -m1 -o1+s -f-1 -t2 -a1",
        "-d1 -o1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
        "-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s -a1",
        "-f-1 -n {sni} -Qr -s2+s -r3 -o20 -t4 -a1",
        "-n {sni} -Qr -d5+sm -f3+sm -o2 -t4 -a1",
        "-o1 -a1 -Ar -q1 -a1 -At -f-1 -r1+s -a1",
        "-q1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
        "-s4+sn -r9+s -Qr -n {sni} -S -a1",
        "-o1 -d1 -r1+s -S -s1+s -d3+s -a1",
        "-q1+s -s29+s -o5+s -f-1 -S -a1",
        "-n {sni} -Qr -m2 -f-1 -d7 -a1",
        "-d1 -s1+s -r1+s -f-1 -t8 -a1",
        "-o1 -a1 -An -f1+nme -t6 -a1",
        "-n {sni} -Qr -f-1 -r1+s -a1",
        "-n {sni} -Qr -d1:3 -f-1 -a1",
        "-s1 -d3+s -a1 -At -r1+s -a1",
        "-f-1 -t8 -n {sni} -s1+s -a1",
        "-n {sni} -Qr -d1 -f-1 -a1",
        "-f64+se -n {sni} -t5 -a1",
        "-o1 -a1 -At,r,s -d1 -a1",
        "-d1+s -o2 -s5 -r5 -a1",
        "-r8 -o2 -s7 -q4+s -a1",
        "-o1 -f-1 -r-5+se -a1",
        "-d6+s -q4+hm -o2 -a1",
        "-s5+s -s35+s -m4 -a1",
        "-f-1+sm -t7 -m2 -a1",
        "-o1 -r-5+se -a1",
        "-o1+s -d3+s -a1",
        "-o1 -s4 -s6 -a1",
        "-q1 -r25+s -a1",
        "-d1 -s3+s -a1",
        "-o3 -d7 -a1",
        "-d7 -s2 -a1",
    };

    // Параметры, которые управляет само приложение (адрес/порт), и опасные внутри процесса приложения.
    private static final String SHORT_WITH_VALUE = "ipwyPxI";
    private static final String SHORT_FLAGS = "DEhv";
    private static final String[] LONG_WITH_VALUE = {"--ip", "--port", "--pidfile", "--cache-file", "--protect-path", "--debug", "--conn-ip"};
    private static final String[] LONG_FLAGS = {"--daemon", "--transparent", "--help", "--version"};

    /** Аргументы для движка: подставляет SNI, разбивает строку и убирает запрещённые параметры. */
    public static String[] buildArgs(String command, String sni) {
        String cmd = command == null ? "" : command;
        if (TextUtils.isEmpty(sni)) {
            sni = DEFAULT_SNI;
        }
        cmd = cmd.replace(SNI_PLACEHOLDER, sni);
        List<String> tokens = shellSplit(cmd);
        List<String> result = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int forbidden = forbiddenKind(token);
            if (forbidden == 0) {
                result.add(token);
            } else if (forbidden == 2) {
                i++;
            }
        }
        return result.toArray(new String[0]);
    }

    /** Нормализованная форма для отображения/сохранения: одна строка, одиночные пробелы. */
    public static String normalize(String command) {
        if (command == null) {
            return "";
        }
        return command.trim().replaceAll("\\s+", " ");
    }

    /** null, если строка похожа на стратегию ciadpi, иначе текст ошибки. */
    public static String validate(String command) {
        List<String> tokens = shellSplit(command == null ? "" : command);
        if (tokens.isEmpty()) {
            return "Стратегия пустая";
        }
        if (!tokens.get(0).startsWith("-")) {
            return "Стратегия должна начинаться с параметра, например -d1";
        }
        return null;
    }

    /** 0 — разрешён, 1 — запрещённый флаг, 2 — запрещённый параметр, значение в следующем токене. */
    private static int forbiddenKind(String token) {
        if (token.startsWith("--")) {
            for (String name : LONG_FLAGS) {
                if (token.equals(name)) {
                    return 1;
                }
            }
            for (String name : LONG_WITH_VALUE) {
                if (token.equals(name)) {
                    return 2;
                }
                if (token.startsWith(name + "=")) {
                    return 1;
                }
            }
            return 0;
        }
        if (token.length() >= 2 && token.charAt(0) == '-') {
            char opt = token.charAt(1);
            if (SHORT_FLAGS.indexOf(opt) >= 0) {
                return 1;
            }
            if (SHORT_WITH_VALUE.indexOf(opt) >= 0) {
                return token.length() == 2 ? 2 : 1;
            }
        }
        return 0;
    }

    static List<String> shellSplit(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private MglaDpiStrategies() {
    }
}
