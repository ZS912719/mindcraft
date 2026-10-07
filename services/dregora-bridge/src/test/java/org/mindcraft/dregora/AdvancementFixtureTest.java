package org.mindcraft.dregora;

import net.minecraft.advancements.*;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.util.ResourceLocation;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdvancementFixtureTest {
    @Test public void nativeLoaderRegistersTheFixtureAndConsumesMutableInput() throws Exception {
        Constructor<Advancement.Builder> constructor = Advancement.Builder.class.getDeclaredConstructor(ResourceLocation.class,
            DisplayInfo.class, AdvancementRewards.class, Map.class, String[][].class);
        constructor.setAccessible(true);
        Criterion criterion = new Criterion(new ImpossibleTrigger.Instance());
        Advancement.Builder builder = constructor.newInstance(null, null, AdvancementRewards.EMPTY,
            Collections.singletonMap("manual", criterion), new String[][] {{"manual"}});
        ResourceLocation id = new ResourceLocation("mindcraft", "test_requirements");
        Map<ResourceLocation, Advancement.Builder> definitions = new HashMap<>();
        definitions.put(id, builder);
        AdvancementList list = new AdvancementList();
        list.loadAdvancements(definitions);
        assertTrue("The native loader removes resolved input entries", definitions.isEmpty());
        assertNotNull(list.getAdvancement(id));
        assertTrue(list.getAdvancement(id).getCriteria().containsKey("manual"));
    }
}
