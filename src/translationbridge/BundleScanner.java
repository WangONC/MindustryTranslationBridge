package translationbridge;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.I18NBundle;
import arc.util.Log;
import arc.util.io.PropertiesUtils;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.mod.Mods.LoadedMod;

import java.io.Reader;
import java.util.*;

final class BundleScanner{
    private final CacheStore cache;
    private final String ownModId;
    private Map<String, String> runtimeSourceSnapshot;

    BundleScanner(CacheStore cache, String ownModId){
        this.cache = cache;
        this.ownModId = ownModId;
    }

    Models.ScanResult scanCurrent(){
        return scanCurrent(false);
    }

    Models.ScanResult scanCurrent(boolean includeDisabled){
        cache.ensureLoaded();
        Models.ScanResult result = new Models.ScanResult();
        result.locale = currentLocale();
        result.includesDisabled = includeDisabled;

        ArrayList<LoadedMod> mods = new ArrayList<LoadedMod>();
        for(LoadedMod mod : Vars.mods.getMods()){
            if(mod == null || ownModId.equals(mod.name)) continue;
            if(!includeDisabled && !mod.enabled()) continue;
            mods.add(mod);
        }
        Collections.sort(mods, (a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));

        for(LoadedMod mod : mods) result.mods.add(scanMod(mod, result.locale));
        return result;
    }

    Models.ModStatus scanMod(LoadedMod mod, String locale){
        Models.ModStatus status = new Models.ModStatus();
        status.mod = mod;
        status.modId = mod.name;
        status.displayName = displayName(mod);
        status.enabled = mod.enabled();

        try{
            Map<String, String> source = readSource(mod);
            if(source.isEmpty()){
                status.supported = false;
                status.bundleSupported = false;
                return status;
            }
            status.supported = true;
            status.bundleSupported = true;
            Map<String, String> target = readTargetProperties(mod, locale);
            List<String> keys = new ArrayList<String>(source.keySet());
            Collections.sort(keys);

            for(String key : keys){
                String text = source.get(key);
                if(!TranslationCore.nonEmpty(text)) continue;
                String hash = TranslationCore.sha256(text);
                Models.CacheEntry cached = cache.get(mod.name, locale, key);
                boolean baseSourceLocale = TranslationCore.isBaseSourceLocale(locale);
                String nativeTranslation = baseSourceLocale ? text : target.get(key);
                boolean nativeAvailable = baseSourceLocale || TranslationCore.nonEmpty(nativeTranslation);
                Models.KeyState state = TranslationCore.classify(
                    text, nativeTranslation, nativeAvailable, baseSourceLocale, cached
                );
                String cachedTranslation = cached == null ? "" : cached.translation;

                status.keys.add(new Models.KeyStatus(
                    key,
                    text,
                    hash,
                    nativeTranslation,
                    cachedTranslation,
                    nativeAvailable,
                    state
                ));
                status.total++;
                if(state == Models.KeyState.official) status.official++;
                if(state == Models.KeyState.cacheValid) status.cached++;
                if(state == Models.KeyState.suspectUntranslated) status.suspectUntranslated++;
                if(state == Models.KeyState.missing) status.missing++;
                if(state == Models.KeyState.expired) status.expired++;
            }
        }catch(Throwable e){
            status.error = shortError(e);
            status.supported = false;
            Log.err("[Translation Bridge] Failed to scan mod '@'.", mod.name, e);
        }
        return status;
    }

    Map<String, String> readSource(LoadedMod mod){
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();

        // 1) Explicit root/source bundle is authoritative when the mod provides one.
        Fi bundles = mod.root.child("bundles");
        Fi source = bundles.child("bundle.properties");
        if(source.exists() && !source.isDirectory()) out.putAll(readProperties(source));

        // 2) Official Mindustry HJSON/JSON inline localization fallback.
        // ContentParser.readBundle() treats top-level "name" and "description" as localization
        // source text. Read those files directly so bundle-less mods do not depend on runtime
        // bundle timing/snapshots.
        readInlineContentSource(mod, out);

        // 3) Runtime source snapshot covers generated/late-bound standard content keys.
        Map<String, String> runtimeSource = readRuntimeSourceProperties();
        for(Map.Entry<String, String> entry : runtimeSource.entrySet()){
            if(!out.containsKey(entry.getKey()) && isStandardContentKeyForMod(entry.getKey(), mod.name)){
                out.put(entry.getKey(), entry.getValue());
            }
        }

        // 4) Locale bundles may contain keys omitted by bundle.properties. Only accept those keys
        // when the root runtime source has a non-empty English/source value; never treat a foreign
        // locale string as source text.
        if(!runtimeSource.isEmpty() && bundles.exists() && bundles.isDirectory()){
            for(Fi file : bundles.list()){
                String name = file.name();
                if(file.isDirectory() || !name.startsWith("bundle_") || !name.endsWith(".properties")) continue;
                Map<String, String> localeValues = readProperties(file);
                for(String key : localeValues.keySet()){
                    if(out.containsKey(key)) continue;
                    String fallback = runtimeSource.get(key);
                    if(TranslationCore.nonEmpty(fallback)) out.put(key, fallback);
                }
            }
        }
        return out;
    }

    private void readInlineContentSource(LoadedMod mod, Map<String, String> out){
        Fi contentRoot = mod.root.child("content");
        if(!contentRoot.exists() || !contentRoot.isDirectory()) return;

        HashSet<String> visited = new HashSet<String>();

        for(ContentType type : ContentType.all){
            if(type.contentClass == null) continue;

            // Accept both current folder names and historical/singular aliases used by mods.
            // Files that Mindustry actually loads will resolve to the same content key below.
            LinkedHashSet<String> folderNames = new LinkedHashSet<String>();
            String typeName = type.name().toLowerCase(Locale.ROOT);
            folderNames.add(type.folderName);
            folderNames.add(typeName);
            folderNames.add(typeName.endsWith("s") ? typeName : typeName + "s");

            for(String folderName : folderNames){
                Fi folder = contentRoot.child(folderName);
                if(!folder.exists() || !folder.isDirectory()) continue;

                for(Fi file : folder.findAll(f -> f.extEquals("hjson") || f.extEquals("json"))){
                    String visitId = type.name() + "\u0000" + file.path();
                    if(!visited.add(visitId)) continue;
                    readInlineContentFile(mod, type, file, out);
                }
            }
        }
    }

    private void readInlineContentFile(LoadedMod mod, ContentType type, Fi file, Map<String, String> out){
        try{
            Jval value = Jval.read(file.readString("UTF-8"));
            if(value == null || !value.isObject()) return;

            String inlineName = value.getString("name", null);
            String inlineDescription = value.getString("description", null);
            if(!TranslationCore.nonEmpty(inlineName) && !TranslationCore.nonEmpty(inlineDescription)) return;

            String baseName = file.nameWithoutExtension();

            // Mirror ContentParser.locate(): an unprefixed existing content name means this file
            // patches vanilla/previous content; otherwise Mindustry creates <modId>-<fileName>.
            String contentName = Vars.content.getByName(type, baseName) != null
                ? baseName
                : mod.name + "-" + baseName;
            String entryBase = type.name() + "." + contentName + ".";

            if(TranslationCore.nonEmpty(inlineName) && !out.containsKey(entryBase + "name")){
                out.put(entryBase + "name", inlineName);
            }
            if(TranslationCore.nonEmpty(inlineDescription) && !out.containsKey(entryBase + "description")){
                out.put(entryBase + "description", inlineDescription);
            }
        }catch(Throwable e){
            // One malformed/unsupported content file must not make the entire mod unscannable.
            Log.warn("[Translation Bridge] Skipping inline localization source '@': @", file.path(), shortError(e));
        }
    }

    void captureRuntimeSourceSnapshot(){
        runtimeSourceSnapshot = readLiveRuntimeSourceProperties();
    }

    Map<String, String> readTargetProperties(LoadedMod mod, String locale){
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        Fi bundles = mod.root.child("bundles");
        for(String suffix : TranslationCore.localeSuffixes(locale)){
            Fi file = bundles.child("bundle_" + suffix + ".properties");
            if(file.exists() && !file.isDirectory()) result.putAll(readProperties(file));
        }
        return result;
    }

    private Map<String, String> readRuntimeSourceProperties(){
        if(runtimeSourceSnapshot != null) return runtimeSourceSnapshot;
        return readLiveRuntimeSourceProperties();
    }

    private Map<String, String> readLiveRuntimeSourceProperties(){
        I18NBundle bundle = Core.bundle;
        if(bundle == null) return Collections.emptyMap();
        while(bundle.getParent() != null) bundle = bundle.getParent();

        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        ObjectMap<String, String> values = bundle.getProperties();
        if(values == null) return out;
        for(ObjectMap.Entry<String, String> entry : values.entries()) out.put(entry.key, entry.value);
        return out;
    }

    private static boolean isStandardContentKeyForMod(String key, String modId){
        if(key == null || modId == null || modId.isEmpty()) return false;
        String marker = "." + modId + "-";
        int split = key.indexOf('.');
        if(split <= 0 || !key.startsWith(marker, split)) return false;
        String typeName = key.substring(0, split);
        for(ContentType type : ContentType.all){
            if(type.name().equals(typeName)) return true;
        }
        return false;
    }

    private Map<String, String> readProperties(Fi file){
        ObjectMap<String, String> values = new ObjectMap<String, String>();
        Reader reader = null;
        try{
            reader = file.reader("UTF-8");
            PropertiesUtils.load(values, reader);
        }catch(Exception e){
            throw new RuntimeException("Failed reading " + file.path(), e);
        }finally{
            if(reader != null){
                try{ reader.close(); }catch(Exception ignored){}
            }
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for(ObjectMap.Entry<String, String> e : values.entries()) out.put(e.key, e.value);
        return out;
    }

    private static String displayName(LoadedMod mod){
        return mod.meta.displayName == null || mod.meta.displayName.trim().isEmpty() ? mod.name : mod.meta.displayName;
    }

    static String currentLocale(){
        String value = "";
        try{
            if(arc.Core.settings != null){
                String configured = arc.Core.settings.getString("locale", "default");
                if(configured != null && !configured.isEmpty() && !"default".equals(configured)) value = configured;
            }
        }catch(Throwable ignored){ }

        if(value.isEmpty()) value = Locale.getDefault().toString();
        if(value.isEmpty() && arc.Core.bundle != null && arc.Core.bundle.getLocale() != null) value = arc.Core.bundle.getLocale().toString();
        if(value.isEmpty()) value = "en";
        return value.replace('-', '_').replace("in_ID", "id_ID");
    }

    private static String shortError(Throwable e){
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
