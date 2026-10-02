package translationbridge;

import arc.Core;

final class BridgeStrings{
    private BridgeStrings(){}

    static String modName(){
        return get("translationbridge.mod.name", "Mindustry Translation Bridge");
    }

    static String modDescription(){
        return get("translationbridge.mod.description", "Scans standard mod bundles, imports/exports offline translations, and applies them from an independent cache.");
    }

    static String settingsName(){
        return get("translationbridge.settings.name", "Mod Translation Tool");
    }

    private static String get(String key, String fallback){
        try{
            return Core.bundle == null ? fallback : Core.bundle.get(key, fallback);
        }catch(Throwable ignored){
            return fallback;
        }
    }
}
