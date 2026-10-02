package translationbridge;

import mindustry.mod.Mods.LoadedMod;

import java.util.*;

final class Models{
    private Models(){}

    /** Effective priority: valid Bridge cache > native target-locale bundle > source bundle. */
    enum KeyState{ cacheValid, official, suspectUntranslated, missing, expired }

    static final class CacheEntry{
        String mod;
        String locale;
        String key;
        String source;
        String sourceHash;
        String translation;

        CacheEntry(){}

        CacheEntry(String mod, String locale, String key, String source, String sourceHash, String translation){
            this.mod = mod;
            this.locale = locale;
            this.key = key;
            this.source = source;
            this.sourceHash = sourceHash;
            this.translation = translation;
        }
    }

    static final class KeyStatus{
        String key;
        String source;
        String sourceHash;
        String nativeTranslation;
        String cachedTranslation;
        String currentTranslation;
        boolean nativeAvailable;
        boolean metadata;
        KeyState state;

        KeyStatus(String key, String source, String sourceHash, String nativeTranslation, String cachedTranslation, boolean nativeAvailable, KeyState state){
            this(key, source, sourceHash, nativeTranslation, cachedTranslation, nativeAvailable, state, false);
        }

        KeyStatus(String key, String source, String sourceHash, String nativeTranslation, String cachedTranslation, boolean nativeAvailable, KeyState state, boolean metadata){
            this.key = key;
            this.source = source;
            this.sourceHash = sourceHash;
            this.nativeTranslation = nativeTranslation == null ? "" : nativeTranslation;
            this.cachedTranslation = cachedTranslation == null ? "" : cachedTranslation;
            this.nativeAvailable = nativeAvailable;
            this.metadata = metadata;
            this.state = state;
            this.currentTranslation = state == KeyState.cacheValid ? this.cachedTranslation : this.nativeTranslation;
        }

        boolean pending(){
            return state == KeyState.missing || state == KeyState.suspectUntranslated || state == KeyState.expired;
        }

        boolean effectiveTranslated(){
            // A source-identical native value is intentionally not counted as completed until it
            // has been reviewed and stored as a valid Bridge cache entry.
            return state != KeyState.suspectUntranslated && (state == KeyState.cacheValid || nativeAvailable);
        }

        String identity(String modId){
            return TranslationBridgeService.entryId(modId, key);
        }
    }

    static final class ModStatus{
        LoadedMod mod;
        String modId;
        String displayName;
        boolean enabled;
        boolean supported;
        boolean bundleSupported;
        boolean metadataIncluded;
        int metadataCount;
        String error;
        int total;
        int official;
        int cached;
        int suspectUntranslated;
        int missing;
        int expired;
        final List<KeyStatus> keys = new ArrayList<KeyStatus>();

        int translated(){
            int count = 0;
            for(KeyStatus key : keys) if(key.effectiveTranslated()) count++;
            return count;
        }

        int pending(){
            int count = 0;
            for(KeyStatus key : keys) if(key.pending()) count++;
            return count;
        }
    }

    static final class ScanResult{
        String locale;
        boolean includesDisabled;
        boolean includesMetadata;
        final List<ModStatus> mods = new ArrayList<ModStatus>();

        int pending(){
            int count = 0;
            for(ModStatus mod : mods) count += mod.pending();
            return count;
        }

        int totalKeys(){
            int count = 0;
            for(ModStatus mod : mods) if(mod.supported && mod.error == null) count += mod.total;
            return count;
        }
    }

    static final class ImportWarning{
        String mod;
        String key;
        String reason;

        ImportWarning(String mod, String key, String reason){
            this.mod = mod;
            this.key = key;
            this.reason = reason;
        }
    }

    static final class ImportPlan{
        String locale;
        final List<CacheEntry> accepted = new ArrayList<CacheEntry>();
        final List<ImportWarning> warnings = new ArrayList<ImportWarning>();
        final List<String> rejected = new ArrayList<String>();
        int emptyIgnored;
        int duplicateIgnored;

        boolean hasWarnings(){ return !warnings.isEmpty(); }

        int warningEntryCount(){
            HashSet<String> unique = new HashSet<String>();
            for(ImportWarning warning : warnings) unique.add(warning.mod + "\u0000" + warning.key);
            return unique.size();
        }
    }
}
