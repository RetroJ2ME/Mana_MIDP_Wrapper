/*
 * Copyright (C) 2026 Magstic
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import doja.tools.scratchpad.Scratchpad;
import doja.tools.jam.Jam;

import java.io.File;
import java.io.IOException;

/** 清除原始 SP 擷取檔中的個人存檔與設定，只封裝空白初始狀態。 */
public final class GameScratchpad implements Scratchpad.Schema {
    private static final int SLOT_SIZE = 10240;
    private static final int SAVE_SIZE = SLOT_SIZE * 2;
    private static final int SETTINGS_OFFSET = SAVE_SIZE - 32;
    private static final int PURCHASE_SIZE = 2;

    public void apply(Scratchpad sp) throws IOException {
        String jam = System.getProperty("seiken.jam");
        if (jam == null) throw new IOException("missing Seiken JAM path");
        String sizes = Jam.read(new File(jam)).require("SPsize");
        if (!"20480,2".equals(sizes.replace(" ", ""))) {
            throw new IOException("Seiken SP partitions changed: " + sizes);
        }
        if (sp.size() != SAVE_SIZE + PURCHASE_SIZE) {
            throw new IOException("Seiken SP size changed: " + sp.size());
        }
        if (!sp.resources().isEmpty()) {
            throw new IOException("Seiken SP contains resources; inspect before clearing mutable data");
        }

        int occupied = 0;
        for (int slot = 0; slot < 2; slot++) {
            Scratchpad.Region data = sp.region(slot * SLOT_SIZE, SLOT_SIZE);
            int status = data.u16be(0);
            if (status == 0) continue;
            if (status != 1 || data.u16be(4) != 108) {
                throw new IOException("unrecognized Seiken save slot " + slot);
            }
            int checksum = 0;
            for (int offset = 4; offset < 10208; offset += 2) checksum += data.u16be(offset);
            if ((checksum & 0xffff) != data.u16be(2)) {
                throw new IOException("invalid Seiken save slot " + slot + " checksum");
            }
            occupied++;
        }

        int settings = sp.region(SETTINGS_OFFSET, 2).u16be(0);
        if (settings != 0 && settings != 1) {
            throw new IOException("unrecognized Seiken settings marker " + settings);
        }
        if (sp.region(SAVE_SIZE + 1, 1).u8(0) != 0) {
            throw new IOException("unrecognized Seiken purchase flag layout");
        }

        sp.state("save slots and settings", 0, SAVE_SIZE).clear();
        sp.state("purchase flag", SAVE_SIZE, PURCHASE_SIZE).clear();
        System.out.println("GameScratchpad: removed " + occupied + " preloaded saves and mutable settings");
    }
}
