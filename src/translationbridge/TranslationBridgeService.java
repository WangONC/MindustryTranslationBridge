package translationbridge;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.mod.Mods.LoadedMod;

import java.util.*;

final class TranslationBridgeService{
    static final String EXPORT_FORMAT = "mindustry-translation-bridge-v1";
    static final String OWN_MOD_ID = "mindustry-translation-bridge";
    static final String TRANSLATE_METADATA_KEY = "translation-bridge-translate-metadata";

    static final String META_DISPLAY_NAME = "__meta.displayName";
    static final String META_SUBTITLE = "__meta.subtitle";
    static final String META_DESCRIPTION = "__meta.description";

    private final CacheStore cache;
    private final BundleScanner scanner;
    private final HashSet<String> touchedMods = new HashSet<String>();
    private final HashMap<String, MetadataSnapshot> originalMetadata = new HashMap<String, MetadataSnapshot>();

    private static final class MetadataSnapshot{
        final String displayName;
        final String subtitle;
        final String description;

        MetadataSnapshot(LoadedMod mod){
            this.displayName = clean(mod == null || mod.meta == null ? null : mod.meta.displayName);
            this.subtitle = clean(mod == null || mod.meta == null ? null : mod.meta.subtitle);
            this.description = clean(mod == null || mod.meta == null ? null : mod.meta.description);
        }

        String get(String key){
            if(META_DISPLAY_NAME.equals(key)) return displayName;
            if(META_SUBTITLE.equals(key)) return subtitle;
            if(META_DESCRIPTION.equals(key)) return description;
            return null;
        }

        private static String clean(String value){
            return value == null ? "" : value;
        }
    }

    TranslationBridgeService(){
        cache = new CacheStore();
        scanner = new BundleScanner(cache, OWN_MOD_ID);
    }

    CacheStore cache(){ return cache; }

    Models.ScanResult scan(){ return scan(false, false); }

    Models.ScanResult scan(boolean includeDisabled){ return scan(includeDisabled, false); }

    Models.ScanResult scan(boolean includeDisabled, boolean includeMetadata){
        captureOriginalMetadata();
        Models.ScanResult result = scanner.scanCurrent(includeDisabled);
        result.includesMetadata = includeMetadata;
        if(includeMetadata){
            for(Models.ModStatus mod : result.mods) appendMetadataStatus(mod, result.locale);
        }
        return result;
    }

    private void appendMetadataStatus(Models.ModStatus status, String locale){
        LoadedMod mod = status.mod;
        if(mod == null || OWN_MOD_ID.equals(status.modId)) return;

        MetadataSnapshot snapshot = metadataSnapshot(mod);
        ArrayList<Models.KeyStatus> metaKeys = new ArrayList<Models.KeyStatus>();
        addMetadataKey(metaKeys, status, locale, META_DISPLAY_NAME, snapshot.displayName);
        addMetadataKey(metaKeys, status, locale, META_SUBTITLE, snapshot.subtitle);
        addMetadataKey(metaKeys, status, locale, META_DESCRIPTION, snapshot.description);

        if(metaKeys.isEmpty()) return;
        status.keys.addAll(0, metaKeys);
        status.metadataIncluded = true;
        status.metadataCount = metaKeys.size();
        status.supported = true;
    }

    private void addMetadataKey(List<Models.KeyStatus> out, Models.ModStatus status, String locale, String key, String source){
        if(!TranslationCore.nonEmpty(source)) return;
        Models.CacheEntry cached = cache.get(status.modId, locale, key);
        boolean baseSourceLocale = TranslationCore.isBaseSourceLocale(locale);
        boolean nativeAvailable = baseSourceLocale;
        String nativeTranslation = nativeAvailable ? source : null;
        Models.KeyState state = TranslationCore.classify(
            source, nativeTranslation, nativeAvailable, baseSourceLocale, cached
        );
        String cachedTranslation = cached == null ? "" : cached.translation;

        out.add(new Models.KeyStatus(
            key,
            source,
            TranslationCore.sha256(source),
            nativeTranslation,
            cachedTranslation,
            nativeAvailable,
            state,
            true
        ));
        status.total++;
        if(state == Models.KeyState.official) status.official++;
        if(state == Models.KeyState.cacheValid) status.cached++;
        if(state == Models.KeyState.suspectUntranslated) status.suspectUntranslated++;
        if(state == Models.KeyState.missing) status.missing++;
        if(state == Models.KeyState.expired) status.expired++;
    }

    int applyCacheAfterBundleMerge(){
        touchedMods.clear();
        try{
            captureOriginalMetadata();
            cache.ensureLoaded();
            String locale = BundleScanner.currentLocale();
            List<Models.CacheEntry> all = cache.all();
            if(all.isEmpty()) return 0;

            Map<String, List<Models.CacheEntry>> byMod = new HashMap<String, List<Models.CacheEntry>>();
            for(Models.CacheEntry entry : all){
                if(!locale.equals(entry.locale) || !TranslationCore.nonEmpty(entry.translation)) continue;
                List<Models.CacheEntry> list = byMod.get(entry.mod);
                if(list == null){
                    list = new ArrayList<Models.CacheEntry>();
                    byMod.put(entry.mod, list);
                }
                list.add(entry);
            }
            if(byMod.isEmpty()) return 0;

            boolean metadataEnabled = metadataTranslationEnabled();
            int bundleApplied = 0;
            int metadataApplied = 0;

            for(LoadedMod mod : Vars.mods.orderedMods()){
                if(mod == null || !mod.enabled() || OWN_MOD_ID.equals(mod.name)) continue;
                List<Models.CacheEntry> entries = byMod.get(mod.name);
                if(entries == null) continue;

                Map<String, String> bundleSource = null;
                for(Models.CacheEntry entry : entries){
                    if(isMetadataKey(entry.key)){
                        if(!metadataEnabled) continue;
                        String now = metadataSource(mod, entry.key);
                        if(!cacheMatches(now, entry)) continue;
                        applyMetadataTranslation(mod, entry.key, entry.translation);
                        metadataApplied++;
                        continue;
                    }

                    if(bundleSource == null) bundleSource = scanner.readSource(mod);
                    String now = bundleSource.get(entry.key);
                    if(!cacheMatches(now, entry)) continue;

                    Core.bundle.getProperties().put(entry.key, entry.translation);
                    bundleApplied++;
                    touchedMods.add(mod.name);
                }
            }
            int total = bundleApplied + metadataApplied;
            Log.info("[Translation Bridge] Applied @ cached translations for locale '@' (@ bundle, @ metadata).",
                total, locale, bundleApplied, metadataApplied);
            return total;
        }catch(Throwable e){
            Log.err("[Translation Bridge] Failed to apply cached translations; continuing without them.", e);
            return 0;
        }
    }

    private static boolean cacheMatches(String now, Models.CacheEntry entry){
        if(!TranslationCore.nonEmpty(now)) return false;
        String hash = TranslationCore.sha256(now);
        return hash.equals(entry.sourceHash) && now.equals(entry.source);
    }

    private static boolean metadataTranslationEnabled(){
        try{
            return Core.settings == null || Core.settings.getBool(TRANSLATE_METADATA_KEY, true);
        }catch(Throwable ignored){
            return true;
        }
    }

    private void applyMetadataTranslation(LoadedMod mod, String key, String translation){
        if(META_DISPLAY_NAME.equals(key)){
            mod.meta.displayName = translation;
        }else if(META_SUBTITLE.equals(key)){
            mod.meta.subtitle = translation;
        }else if(META_DESCRIPTION.equals(key)){
            mod.meta.description = translation;
        }
    }

    void refreshStandardContentAfterOverrides(){
        if(touchedMods.isEmpty() || Vars.content == null) return;
        try{
            for(var group : Vars.content.getContentMap()){
                for(var content : group){
                    if(!(content instanceof UnlockableContent u) || u.minfo.mod == null || !touchedMods.contains(u.minfo.mod.name)) continue;
                    String base = u.getContentType() + "." + u.name;
                    u.localizedName = Core.bundle.get(base + ".name", u.name);
                    u.description = Core.bundle.getOrNull(base + ".description");
                    u.details = Core.bundle.getOrNull(base + ".details");
                    u.credit = Core.bundle.getOrNull(base + ".credit");
                }
            }
        }catch(Throwable e){
            Log.err("[Translation Bridge] Bundle override applied, but refreshing cached content labels failed.", e);
        }
    }

    void onModContentLoaded(){
        scanner.captureRuntimeSourceSnapshot();
        applyCacheAfterBundleMerge();
        refreshStandardContentAfterOverrides();
    }

    String exportJson(Models.ScanResult scan, Set<String> selectedModIds){
        LinkedHashSet<String> entries = new LinkedHashSet<String>();
        for(Models.ModStatus mod : scan.mods){
            if(!selectedModIds.contains(mod.modId) || !mod.supported || mod.error != null) continue;
            for(Models.KeyStatus key : mod.keys) if(key.pending()) entries.add(entryId(mod.modId, key.key));
        }
        return exportSelectedEntries(scan, entries);
    }

    String exportSelectedEntries(Models.ScanResult scan, Set<String> selectedEntryIds){
        return exportSelectedEntries(scan, null, selectedEntryIds);
    }

    String exportSelectedEntries(Models.ScanResult scan, Set<String> selectedModIds, Set<String> selectedEntryIds){
        Jval root = Jval.newObject();
        root.put("format", EXPORT_FORMAT);
        root.put("targetLocale", scan.locale);
        Jval array = Jval.newArray();

        for(Models.ModStatus mod : scan.mods){
            if(!mod.supported || mod.error != null) continue;
            if(selectedModIds != null && !selectedModIds.contains(mod.modId)) continue;
            for(Models.KeyStatus key : mod.keys){
                if(!selectedEntryIds.contains(entryId(mod.modId, key.key))) continue;
                String translation = key.state == Models.KeyState.suspectUntranslated
                    ? key.nativeTranslation
                    : (key.effectiveTranslated() ? key.currentTranslation : "");
                array.add(Jval.newObject()
                    .put("mod", mod.modId)
                    .put("key", key.key)
                    .put("source", key.source)
                    .put("sourceHash", key.sourceHash)
                    .put("translation", translation == null ? "" : translation));
            }
        }
        root.put("entries", array);
        return root.toString(Jformat.formatted);
    }

    static String entryId(String modId, String key){
        return modId + "\u0000" + key;
    }

    static boolean isMetadataKey(String key){
        return META_DISPLAY_NAME.equals(key) || META_SUBTITLE.equals(key) || META_DESCRIPTION.equals(key);
    }

    static String metadataLabel(String key){
        if(META_DISPLAY_NAME.equals(key)) return "Mod 显示名称";
        if(META_SUBTITLE.equals(key)) return "Mod 副标题";
        if(META_DESCRIPTION.equals(key)) return "Mod 描述";
        return key;
    }

    Models.ImportPlan parseImport(Fi file){
        return parseImportText(file.readString("UTF-8"));
    }

    Models.ImportPlan parseImportText(String text){
        if(text == null || text.trim().isEmpty()) throw new IllegalArgumentException("剪贴板/文件中没有 JSON 内容。 ");
        Jval root;
        try{
            root = Jval.read(text);
        }catch(Throwable e){
            throw new IllegalArgumentException("JSON 解析失败：" + finalMessage(e), e);
        }
        if(root == null || !root.isObject()) throw new IllegalArgumentException("文件根节点必须是 JSON object。 ");
        if(!EXPORT_FORMAT.equals(root.getString("format", ""))) throw new IllegalArgumentException("format 不正确，不是 Translation Bridge v1 文件。 ");

        String locale = root.getString("targetLocale", "");
        String current = BundleScanner.currentLocale();
        if(!current.equals(locale)){
            throw new IllegalArgumentException("目标语言不匹配：文件为 " + locale + "，当前游戏为 " + current + "。请切换语言并重启后再导入。 ");
        }
        Jval entries = root.get("entries");
        if(entries == null || !entries.isArray()) throw new IllegalArgumentException("entries 必须是数组。 ");

        captureOriginalMetadata();
        Models.ImportPlan plan = new Models.ImportPlan();
        plan.locale = locale;
        HashSet<String> seen = new HashSet<String>();
        HashMap<String, Map<String, String>> sourceByMod = new HashMap<String, Map<String, String>>();

        for(Jval raw : entries.asArray()){
            if(raw == null || !raw.isObject()){
                plan.rejected.add("发现非 object 的 entry，已跳过。");
                continue;
            }
            String modId = raw.getString("mod", "");
            String key = raw.getString("key", "");
            String sourceField = raw.getString("source", "");
            String hashField = raw.getString("sourceHash", "");
            String translation = raw.getString("translation", "");

            if(modId.isEmpty() || key.isEmpty()){
                plan.rejected.add("entry 缺少 mod 或 key，已跳过。");
                continue;
            }
            String identity = entryId(modId, key);
            if(!seen.add(identity)){
                plan.duplicateIgnored++;
                plan.rejected.add(modId + " / " + key + "：重复 key，已忽略后续项。");
                continue;
            }
            if(!TranslationCore.nonEmpty(translation)){
                plan.emptyIgnored++;
                continue;
            }

            LoadedMod mod = Vars.mods.getMod(modId);
            if(mod == null){
                plan.rejected.add(modId + " / " + key + "：Mod 当前未安装，无法核对原文。");
                continue;
            }
            Map<String, String> sourceMap = sourceByMod.get(modId);
            if(sourceMap == null){
                sourceMap = sourceMapForImport(mod);
                sourceByMod.put(modId, sourceMap);
            }
            String currentSource = sourceMap.get(key);
            if(currentSource == null){
                plan.rejected.add(modId + " / " + key + "：当前 Mod 中已不存在该 key。");
                continue;
            }
            if(!TranslationCore.nonEmpty(currentSource)){
                plan.rejected.add(modId + " / " + key + "：原文为空，没有可翻译内容。");
                continue;
            }
            String currentHash = TranslationCore.sha256(currentSource);
            if(!currentSource.equals(sourceField) || !currentHash.equals(hashField) || !currentHash.equals(TranslationCore.sha256(sourceField))){
                plan.rejected.add(modId + " / " + key + "：原文已变化，该翻译已过期，未写入缓存。");
                continue;
            }

            Models.CacheEntry accepted = new Models.CacheEntry(modId, locale, key, currentSource, currentHash, translation);
            plan.accepted.add(accepted);
            List<String> warnings = TranslationCore.formatWarnings(currentSource, translation);
            for(String warning : warnings) plan.warnings.add(new Models.ImportWarning(modId, key, warning));
        }
        return plan;
    }

    private Map<String, String> sourceMapForImport(LoadedMod mod){
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for(Map.Entry<String, String> e : scanner.readSource(mod).entrySet()){
            if(TranslationCore.nonEmpty(e.getValue())) out.put(e.getKey(), e.getValue());
        }
        MetadataSnapshot snapshot = metadataSnapshot(mod);
        if(TranslationCore.nonEmpty(snapshot.displayName)) out.put(META_DISPLAY_NAME, snapshot.displayName);
        if(TranslationCore.nonEmpty(snapshot.subtitle)) out.put(META_SUBTITLE, snapshot.subtitle);
        if(TranslationCore.nonEmpty(snapshot.description)) out.put(META_DESCRIPTION, snapshot.description);
        return out;
    }

    int commitImport(Models.ImportPlan plan){
        for(Models.CacheEntry entry : plan.accepted) cache.put(entry);
        if(!plan.accepted.isEmpty()) cache.save();
        return plan.accepted.size();
    }

    private void captureOriginalMetadata(){
        if(Vars.mods == null) return;
        for(LoadedMod mod : Vars.mods.getMods()){
            if(mod == null || mod.meta == null || OWN_MOD_ID.equals(mod.name)) continue;
            if(!originalMetadata.containsKey(mod.name)) originalMetadata.put(mod.name, new MetadataSnapshot(mod));
        }
    }

    private MetadataSnapshot metadataSnapshot(LoadedMod mod){
        MetadataSnapshot snapshot = originalMetadata.get(mod.name);
        if(snapshot == null){
            snapshot = new MetadataSnapshot(mod);
            originalMetadata.put(mod.name, snapshot);
        }
        return snapshot;
    }

    private String metadataSource(LoadedMod mod, String key){
        return metadataSnapshot(mod).get(key);
    }

    private static String finalMessage(Throwable e){
        Throwable t = e;
        while(t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
