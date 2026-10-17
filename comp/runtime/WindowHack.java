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

import com.nttdocomo.ui.SoftKeys;

/** 在遊戲切換 UI 顏色時，同步更新 softkey 背景。 */
public final class WindowHack {
    private WindowHack() {}

    public static void load(a images, int slot, String resource) {
        images.a(slot, resource);
        SoftKeys.setColor(resource.charAt(5) - '0');
    }
}
