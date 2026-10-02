package translationbridge;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import arc.Core;

import java.util.*;

final class CacheStore{
    static final String FORMAT = "mindustry-translation-bridge-cache-v1";

    private final LinkedHashMap<String, Models.CacheEntry> entries = new LinkedHashMap<String, Models.CacheEntry>();
    private Fi file;
    private Fi backup;
    private boolean loaded;
    private String startupNotice;

    CacheStore(){}

    synchronized void ensureLoaded(){
        if(loaded) return;
        Fi dir = Core.settings.getDataDirectory().child("mindustry-translation-bridge");
        dir.mkdirs();
        file = dir.child("translations-cache.json");
        backup = dir.child("translations-cache.json.bak");
        loaded = true;

        if(!file.exists()) return;
        try{
            loadFrom(file);
        }catch(Throwable broken){
            Log.err("[Translation Bridge] Cache is corrupted; ignoring main cache.", broken);
            boolean preserved = false;
            try{
                Fi corrupt = dir.child("translations-cache.corrupt-" + System.currentTimeMillis() + ".json");
                file.copyTo(corrupt);
                preserved = true;
            }catch(Throwable ignored){
                Log.err("[Translation Bridge] Failed to preserve corrupt cache copy.", ignored);
            }

            entries.clear();
            if(backup.exists()){
                try{
                    loadFrom(backup);
                    // Restore a known-good main file so the next save does not overwrite the backup with corrupt bytes.
                    file.delete();
                    backup.copyTo(file);
                    startupNotice = "翻译缓存主文件损坏，已从 .bak 备份恢复。游戏本身不受影响。";
                    return;
                }catch(Throwable backupBroken){
                    Log.err("[Translation Bridge] Backup cache is also corrupted.", backupBroken);
                    entries.clear();
                }
            }
            // Do not keep a corrupt file as the live cache. The preserved copy remains available for diagnosis.
            try{ if(preserved && file.exists()) file.delete(); }catch(Throwable ignored){}
            startupNotice = "翻译缓存文件损坏，已保留损坏副本并以空缓存继续启动。游戏本身不受影响。";
        }
    }

    private void loadFrom(Fi source){
        String text = source.readString("UTF-8");
        Jval root = Jval.read(text);
        if(!root.isObject() || !FORMAT.equals(root.getString("format", ""))){
            throw new IllegalArgumentException("Invalid Translation Bridge cache format");
        }
        Jval array = root.get("entries");
        if(array == null || !array.isArray()) throw new IllegalArgumentException("Cache entries must be an array");

        LinkedHashMap<String, Models.CacheEntry> parsed = new LinkedHashMap<String, Models.CacheEntry>();
        for(Jval value : array.asArray()){
            if(value == null || !value.isObject()) continue;
            Models.CacheEntry e = new Models.CacheEntry(
                value.getString("mod", ""),
                value.getString("locale", ""),
                value.getString("key", ""),
                value.getString("source", ""),
                value.getString("sourceHash", ""),
                value.getString("translation", "")
            );
            if(e.mod.isEmpty() || e.locale.isEmpty() || e.key.isEmpty() || e.sourceHash.isEmpty() || !TranslationCore.nonEmpty(e.translation)) continue;
            parsed.put(id(e.mod, e.locale, e.key), e);
        }
        entries.clear();
        entries.putAll(parsed);
    }

    synchronized void save(){
        ensureLoaded();
        Jval root = Jval.newObject();
        root.put("format", FORMAT);
        Jval array = Jval.newArray();

        List<Models.CacheEntry> sorted = new ArrayList<Models.CacheEntry>(entries.values());
        Collections.sort(sorted, new Comparator<Models.CacheEntry>(){
            @Override public int compare(Models.CacheEntry a, Models.CacheEntry b){
                int c = a.mod.compareTo(b.mod);
                if(c != 0) return c;
                c = a.locale.compareTo(b.locale);
                if(c != 0) return c;
                return a.key.compareTo(b.key);
            }
        });

        for(Models.CacheEntry e : sorted){
            array.add(Jval.newObject()
                .put("mod", e.mod)
                .put("locale", e.locale)
                .put("key", e.key)
                .put("source", e.source)
                .put("sourceHash", e.sourceHash)
                .put("translation", e.translation));
        }
        root.put("entries", array);

        Fi tmp = file.sibling(file.name() + ".tmp");
        tmp.writeString(root.toString(Jformat.formatted), false, "UTF-8");
        try{
            if(file.exists()){
                if(backup.exists()) backup.delete();
                file.copyTo(backup);
            }
            if(file.exists()) file.delete();
            tmp.moveTo(file);
        }catch(Throwable e){
            try{
                if(!file.exists() && backup.exists()) backup.copyTo(file);
            }catch(Throwable ignored){ }
            throw e;
        }finally{
            if(tmp.exists()) tmp.delete();
        }
    }

    synchronized Models.CacheEntry get(String mod, String locale, String key){
        ensureLoaded();
        return entries.get(id(mod, locale, key));
    }

    synchronized void put(Models.CacheEntry entry){
        ensureLoaded();
        entries.put(id(entry.mod, entry.locale, entry.key), entry);
    }

    synchronized List<Models.CacheEntry> all(){
        ensureLoaded();
        return new ArrayList<Models.CacheEntry>(entries.values());
    }

    synchronized Set<String> modIds(){
        ensureLoaded();
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for(Models.CacheEntry e : entries.values()) ids.add(e.mod);
        return ids;
    }

    synchronized int clearMod(String mod){
        ensureLoaded();
        int removed = 0;
        Iterator<Map.Entry<String, Models.CacheEntry>> it = entries.entrySet().iterator();
        while(it.hasNext()){
            if(mod.equals(it.next().getValue().mod)){
                it.remove();
                removed++;
            }
        }
        if(removed > 0){
            save();
            syncBackupToCurrent();
        }
        return removed;
    }

    synchronized int clearAll(){
        ensureLoaded();
        int count = entries.size();
        entries.clear();
        save();
        // Clearing is intentional: do not leave a stale backup that could resurrect translations.
        syncBackupToCurrent();
        return count;
    }

    private void syncBackupToCurrent(){
        try{
            if(backup.exists()) backup.delete();
            if(file.exists()) file.copyTo(backup);
        }catch(Throwable e){
            // The live cache is already correct. Backup refresh failure is non-fatal.
            Log.err("[Translation Bridge] Failed to refresh cache backup after clear.", e);
        }
    }

    String consumeStartupNotice(){
        String out = startupNotice;
        startupNotice = null;
        return out;
    }

    private static String id(String mod, String locale, String key){
        return mod + "\u0000" + locale + "\u0000" + key;
    }
}
