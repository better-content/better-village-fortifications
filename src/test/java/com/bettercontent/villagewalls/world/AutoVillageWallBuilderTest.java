package com.bettercontent.villagewalls.world;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoVillageWallBuilderTest {
    @Test
    void queueAdditionsDuringProcessingWaitForTheNextTick() {
        Map<String, Integer> pending = new LinkedHashMap<>();
        pending.put("building", 1);
        pending.put("existing", 2);

        List<String> tickKeys = AutoVillageWallBuilder.snapshotKeys(pending);
        for (String key : tickKeys) {
            if (key.equals("building")) {
                pending.put("discovered-during-build", 3);
                pending.remove(key);
            }
        }

        assertEquals(List.of("building", "existing"), tickKeys);
        assertEquals(List.of("existing", "discovered-during-build"), AutoVillageWallBuilder.snapshotKeys(pending));
    }
}
