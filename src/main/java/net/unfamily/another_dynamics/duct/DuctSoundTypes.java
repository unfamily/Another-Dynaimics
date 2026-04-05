package net.unfamily.another_dynamics.duct;

import net.minecraft.world.level.block.SoundType;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Maps datapack {@code sound} strings (same naming as vanilla {@link SoundType} ids used e.g. in KubeJS docs) to
 * {@link SoundType}. Unknown values fall back to {@link SoundType#COPPER} with a log line.
 */
public final class DuctSoundTypes {
    private DuctSoundTypes() {}

    /** Sound for a duct when the definition is missing or has no {@code sound}: vanilla copper duct feel. */
    public static SoundType forLogicalDuct(String logicalId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .flatMap(DuctDefinition::sound)
                .map(DuctSoundTypes::resolve)
                .orElse(SoundType.COPPER);
    }

    /**
     * Resolves a non-null, non-blank sound name from JSON. For default when the key is omitted, use
     * {@link #forLogicalDuct} instead.
     */
    public static SoundType resolve(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return SoundType.COPPER;
        }
        return switch (soundName.trim().toLowerCase()) {
            case "rock", "stone" -> SoundType.STONE;
            case "glass" -> SoundType.GLASS;
            case "wood" -> SoundType.WOOD;
            case "metal", "metallic", "iron" -> SoundType.METAL;
            case "wool" -> SoundType.WOOL;
            case "sand" -> SoundType.SAND;
            case "gravel" -> SoundType.GRAVEL;
            case "grass" -> SoundType.GRASS;
            case "lily_pad" -> SoundType.LILY_PAD;
            case "snow" -> SoundType.SNOW;
            case "powder_snow" -> SoundType.POWDER_SNOW;
            case "ladder" -> SoundType.LADDER;
            case "anvil" -> SoundType.ANVIL;
            case "slime_block" -> SoundType.SLIME_BLOCK;
            case "honey_block" -> SoundType.HONEY_BLOCK;
            case "coral_block" -> SoundType.CORAL_BLOCK;
            case "bamboo" -> SoundType.BAMBOO;
            case "bamboo_sapling" -> SoundType.BAMBOO_SAPLING;
            case "scaffolding" -> SoundType.SCAFFOLDING;
            case "sweet_berry_bush" -> SoundType.SWEET_BERRY_BUSH;
            case "crop" -> SoundType.CROP;
            case "hard_crop" -> SoundType.HARD_CROP;
            case "vine" -> SoundType.VINE;
            case "nether_wart" -> SoundType.NETHER_WART;
            case "lantern" -> SoundType.LANTERN;
            case "stem" -> SoundType.STEM;
            case "nylium" -> SoundType.NYLIUM;
            case "fungus" -> SoundType.FUNGUS;
            case "roots" -> SoundType.ROOTS;
            case "shroomlight" -> SoundType.SHROOMLIGHT;
            case "weeping_vines" -> SoundType.WEEPING_VINES;
            case "twisting_vines" -> SoundType.TWISTING_VINES;
            case "soul_sand" -> SoundType.SOUL_SAND;
            case "soul_soil" -> SoundType.SOUL_SOIL;
            case "basalt" -> SoundType.BASALT;
            case "wart_block" -> SoundType.WART_BLOCK;
            case "netherrack" -> SoundType.NETHERRACK;
            case "nether_bricks" -> SoundType.NETHER_BRICKS;
            case "nether_sprouts" -> SoundType.NETHER_SPROUTS;
            case "nether_ore" -> SoundType.NETHER_ORE;
            case "bone_block" -> SoundType.BONE_BLOCK;
            case "netherite_block" -> SoundType.NETHERITE_BLOCK;
            case "ancient_debris" -> SoundType.ANCIENT_DEBRIS;
            case "lodestone" -> SoundType.LODESTONE;
            case "chain" -> SoundType.CHAIN;
            case "nether_gold_ore" -> SoundType.NETHER_GOLD_ORE;
            case "gilded_blackstone" -> SoundType.GILDED_BLACKSTONE;
            case "candle" -> SoundType.CANDLE;
            case "amethyst" -> SoundType.AMETHYST;
            case "amethyst_cluster" -> SoundType.AMETHYST_CLUSTER;
            case "small_amethyst_bud" -> SoundType.SMALL_AMETHYST_BUD;
            case "medium_amethyst_bud" -> SoundType.MEDIUM_AMETHYST_BUD;
            case "large_amethyst_bud" -> SoundType.LARGE_AMETHYST_BUD;
            case "tuff" -> SoundType.TUFF;
            case "calcite" -> SoundType.CALCITE;
            case "dripstone_block" -> SoundType.DRIPSTONE_BLOCK;
            case "pointed_dripstone" -> SoundType.POINTED_DRIPSTONE;
            case "copper" -> SoundType.COPPER;
            case "cave_vines" -> SoundType.CAVE_VINES;
            case "spore_blossom" -> SoundType.SPORE_BLOSSOM;
            case "azalea" -> SoundType.AZALEA;
            case "flowering_azalea" -> SoundType.FLOWERING_AZALEA;
            case "moss_carpet" -> SoundType.MOSS_CARPET;
            case "pink_petals" -> SoundType.PINK_PETALS;
            case "moss" -> SoundType.MOSS;
            case "big_dripleaf" -> SoundType.BIG_DRIPLEAF;
            case "small_dripleaf" -> SoundType.SMALL_DRIPLEAF;
            case "rooted_dirt" -> SoundType.ROOTED_DIRT;
            case "hanging_roots" -> SoundType.HANGING_ROOTS;
            case "azalea_leaves" -> SoundType.AZALEA_LEAVES;
            case "sculk_sensor" -> SoundType.SCULK_SENSOR;
            case "sculk_catalyst" -> SoundType.SCULK_CATALYST;
            case "sculk" -> SoundType.SCULK;
            case "sculk_vein" -> SoundType.SCULK_VEIN;
            case "sculk_shrieker" -> SoundType.SCULK_SHRIEKER;
            case "glow_lichen" -> SoundType.GLOW_LICHEN;
            case "deepslate" -> SoundType.DEEPSLATE;
            case "deepslate_bricks" -> SoundType.DEEPSLATE_BRICKS;
            case "deepslate_tiles" -> SoundType.DEEPSLATE_TILES;
            case "polished_deepslate" -> SoundType.POLISHED_DEEPSLATE;
            case "froglight" -> SoundType.FROGLIGHT;
            case "frogspawn" -> SoundType.FROGSPAWN;
            case "mangrove_roots" -> SoundType.MANGROVE_ROOTS;
            case "muddy_mangrove_roots" -> SoundType.MUDDY_MANGROVE_ROOTS;
            case "mud" -> SoundType.MUD;
            case "mud_bricks" -> SoundType.MUD_BRICKS;
            case "packed_mud" -> SoundType.PACKED_MUD;
            case "hanging_sign" -> SoundType.HANGING_SIGN;
            case "nether_wood_hanging_sign" -> SoundType.NETHER_WOOD_HANGING_SIGN;
            case "bamboo_wood_hanging_sign" -> SoundType.BAMBOO_WOOD_HANGING_SIGN;
            case "bamboo_wood" -> SoundType.BAMBOO_WOOD;
            case "nether_wood" -> SoundType.NETHER_WOOD;
            case "cherry_wood" -> SoundType.CHERRY_WOOD;
            case "cherry_sapling" -> SoundType.CHERRY_SAPLING;
            case "cherry_leaves" -> SoundType.CHERRY_LEAVES;
            case "cherry_wood_hanging_sign" -> SoundType.CHERRY_WOOD_HANGING_SIGN;
            case "chiseled_bookshelf" -> SoundType.CHISELED_BOOKSHELF;
            case "suspicious_sand" -> SoundType.SUSPICIOUS_SAND;
            case "suspicious_gravel" -> SoundType.SUSPICIOUS_GRAVEL;
            case "decorated_pot" -> SoundType.DECORATED_POT;
            case "decorated_pot_cracked" -> SoundType.DECORATED_POT_CRACKED;
            default -> {
                AnotherDynamicsMod.LOGGER.warn(
                        "Unknown duct sound '{}', using copper. See KubeJS SoundType / vanilla SoundType names.",
                        soundName);
                yield SoundType.COPPER;
            }
        };
    }
}
