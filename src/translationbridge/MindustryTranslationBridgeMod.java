package translationbridge;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ModContentLoadEvent;
import mindustry.mod.Mod;

/** Entry point. No per-frame listeners, networking, HUD hooks, or modifications to other mods. */
public class MindustryTranslationBridgeMod extends Mod{
    public static final String LLM_PROMPT = """
Translate and review the following Mindustry mod localization JSON.

The target language is specified by `targetLocale`.

For every object in `entries`, modify only the `translation` field.

- If `translation` is empty, translate `source`.
- If it already contains text, review and correct it when necessary.
- For a non-English target locale, if `translation` is identical to `source`, treat it as potentially untranslated: translate normal player-visible text, and keep it unchanged only when it genuinely should remain unchanged (for example an acronym, technical identifier, code, URL, file name, internal ID, or commonly untranslated name).
- Treat whole-value placeholders such as `None`, `null`, `N/A`, `TODO`, `TBD`, or `untranslated` as potentially untranslated player-visible text rather than valid translations.
- Do not modify `format`, `targetLocale`, `mod`, `key`, `source`, or `sourceHash`.

Game context:

Mindustry is a sci-fi factory automation, logistics, tower-defense, and real-time strategy game. Its terminology commonly involves industrial production, power systems, materials, liquids, logistics, payloads, military units, weapons, structures, planets, sectors, and technology.

Translation requirements:

- Write naturally and idiomatically, as if the text were originally written by a native speaker of the target language.
- Prefer established Mindustry terminology when available.
- Keep terminology consistent across related entries.
- For fictional materials, technologies, places, factions, units, and weapons, use a natural translation or transliteration when appropriate.
- Do not leave unnecessary English or Latin-script terms in player-visible text merely because they are fictional or lack an official translation.
- Keep English only when native speakers would normally use it as-is, or when it is clearly an acronym, technical identifier, code, URL, file name, internal ID, or localization key.
- Keep names and UI text concise and appropriate for blocks, units, items, descriptions, tooltips, objectives, and interface text.
- Do not invent lore, mechanics, properties, or meanings not supported by the source.
- Preserve placeholders exactly, such as `{0}` and `{1}`.
- Preserve Mindustry formatting and color tags exactly, such as `[accent]`, `[red]`, `[#ffffff]`, and `[]`.
- Preserve meaningful line breaks, icons, Unicode symbols, punctuation, and special characters.
- Preserve JSON string escaping exactly. Ensure every `translation` remains a valid JSON string.
- Do not add, remove, merge, split, duplicate, or reorder entries.

Before returning, ensure that all protected fields, placeholders, formatting tags, JSON escaping, and entry order remain unchanged.

Return the complete valid JSON only.

Do not include explanations, comments, Markdown code fences, or any text outside the JSON.
""";

    private final TranslationBridgeService service;
    private final BridgeUI bridgeUI;

    public MindustryTranslationBridgeMod(){
        service = new TranslationBridgeService();
        bridgeUI = new BridgeUI(service);
        Events.on(ModContentLoadEvent.class, event -> service.onModContentLoaded());
        Events.on(ClientLoadEvent.class, event -> Core.app.post(bridgeUI::showStartupNoticeIfAny));
    }

    @Override
    public void loadContent(){
        try{
            service.applyCacheAfterBundleMerge();
            localizeOwnMetadata();
        }catch(Throwable e){
            Log.err("[Translation Bridge] Startup translation injection failed; game will continue without bridge overrides.", e);
        }
    }

    @Override
    public void init(){
        try{
            service.cache().ensureLoaded();
            localizeOwnMetadata();
            bridgeUI.installSettingsCategory();
            Log.info("[Translation Bridge] Initialized for locale '@'.", BundleScanner.currentLocale());
        }catch(Throwable e){
            Log.err("[Translation Bridge] Initialization failed; game will continue without bridge UI.", e);
        }
    }

    private void localizeOwnMetadata(){
        try{
            var self = Vars.mods.getMod(getClass());
            if(self == null) return;
            self.meta.displayName = BridgeStrings.modName();
            self.meta.description = BridgeStrings.modDescription();
        }catch(Throwable e){
            Log.err("[Translation Bridge] Failed to localize own mod metadata.", e);
        }
    }
}
