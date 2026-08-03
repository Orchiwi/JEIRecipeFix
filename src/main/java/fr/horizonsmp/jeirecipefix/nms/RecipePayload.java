package fr.horizonsmp.jeirecipefix.nms;

import java.util.List;

/**
 * An encoded recipe payload plus the numbers worth logging. The counts are what actually went into
 * the buffer, so a payload that silently lost recipes is visible in {@code /jrf info} and in the log
 * instead of only showing up as "the client sees nothing".
 *
 * @param bytes            the encoded wire payload
 * @param recipes          recipes actually encoded
 * @param groups           serializer groups (Fabric) or recipe types (NeoForge) encoded
 * @param skippedGroups    ids of groups deliberately left out, with the reason implied by the caller
 * @param skippedRecipes   recipes left out along with those groups
 */
public record RecipePayload(byte[] bytes, int recipes, int groups, List<String> skippedGroups, int skippedRecipes) {

    public boolean isEmpty() {
        return recipes == 0;
    }

    public int size() {
        return bytes.length;
    }
}
