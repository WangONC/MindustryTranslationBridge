package translationbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Pure-Java deterministic helpers; intentionally Android API 21 friendly. */
final class TranslationCore{
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");
    private static final Pattern COLOR = Pattern.compile("\\[(?:#[0-9a-fA-F]{3,8}|[A-Za-z][A-Za-z0-9_-]*|)\\]");

    private TranslationCore(){}

    static String sha256(String text){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for(int i = 0; i < bytes.length; i++){
                int v = bytes[i] & 0xff;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0x0f];
            }
            return new String(out);
        }catch(Exception e){
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    static boolean nonEmpty(String value){
        return value != null && !value.trim().isEmpty();
    }

    static List<String> formatWarnings(String source, String translation){
        List<String> out = new ArrayList<String>();
        if(!tokenCounts(source, PLACEHOLDER).equals(tokenCounts(translation, PLACEHOLDER))){
            out.add("占位符 {0}/{1} 等不一致");
        }
        if(!tokenCounts(source, COLOR).equals(tokenCounts(translation, COLOR))){
            out.add("颜色/样式标记 [accent]/[red]/[] 等不一致");
        }
        if(count(source, '\n') != count(translation, '\n')){
            out.add("换行数量不一致");
        }
        return out;
    }

    static Map<String, Integer> tokenCounts(String text, Pattern pattern){
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while(matcher.find()){
            String token = matcher.group();
            Integer old = counts.get(token);
            counts.put(token, old == null ? 1 : old + 1);
        }
        return counts;
    }

    static int count(String text, char c){
        int count = 0;
        if(text == null) return 0;
        for(int i = 0; i < text.length(); i++) if(text.charAt(i) == c) count++;
        return count;
    }

    static List<String> localeSuffixes(String locale){
        String normalized = locale == null ? "" : locale.replace('-', '_');
        if(normalized.startsWith("in_")) normalized = "id_" + normalized.substring(3);
        if(normalized.equals("in")) normalized = "id";

        ArrayList<String> out = new ArrayList<String>();
        if(normalized.isEmpty()) return out;

        int first = normalized.indexOf('_');
        String language = first < 0 ? normalized : normalized.substring(0, first);
        if(language.isEmpty()) return out;
        out.add(language);
        if(first < 0) return out;

        String rest = normalized.substring(first + 1);
        int secondRel = rest.indexOf('_');
        String country = secondRel < 0 ? rest : rest.substring(0, secondRel);
        String variant = secondRel < 0 ? "" : rest.substring(secondRel + 1);

        if(!country.isEmpty()) out.add(language + "_" + country);
        if(!variant.isEmpty()) out.add(language + "_" + country + "_" + variant);
        return out;
    }

    static boolean isBaseSourceLocale(String locale){
        if(locale == null) return false;
        String normalized = locale.replace('-', '_');
        int split = normalized.indexOf('_');
        String language = split < 0 ? normalized : normalized.substring(0, split);
        return "en".equalsIgnoreCase(language);
    }

    static String normalizeTranslationComparison(String text){
        if(text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    static boolean sameTranslationText(String source, String translation){
        if(!nonEmpty(source) || !nonEmpty(translation)) return false;
        return normalizeTranslationComparison(source).equals(normalizeTranslationComparison(translation));
    }

    /**
     * Common whole-value sentinels used by incomplete localization bundles. These are deliberately
     * conservative: only the entire trimmed value is matched, never prose containing these words.
     * They are marked suspect rather than missing so the original value is preserved for LLM review.
     */
    static boolean placeholderTranslationText(String translation){
        if(!nonEmpty(translation)) return false;
        String value = normalizeTranslationComparison(translation).toLowerCase(Locale.ROOT);
        return value.equals("none")
            || value.equals("null")
            || value.equals("n/a")
            || value.equals("na")
            || value.equals("todo")
            || value.equals("tbd")
            || value.equals("untranslated")
            || value.equals("not translated");
    }

    static Models.KeyState classify(String source, String nativeTranslation, boolean nativeAvailable,
                                    boolean baseSourceLocale, Models.CacheEntry cache){
        if(cache != null){
            String now = sha256(source);
            boolean valid = now.equals(cache.sourceHash) && source.equals(cache.source) && nonEmpty(cache.translation);
            if(valid) return Models.KeyState.cacheValid;
            return Models.KeyState.expired;
        }
        if(!nativeAvailable) return Models.KeyState.missing;
        if(!baseSourceLocale && (sameTranslationText(source, nativeTranslation) || placeholderTranslationText(nativeTranslation))){
            return Models.KeyState.suspectUntranslated;
        }
        return Models.KeyState.official;
    }
}
