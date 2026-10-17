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

/** RMS HACK：離開選項畫面時，保存配置到 RMS。 */
public final class OptionsHack {
    private OptionsHack() {}

    public static int update(c game, int keys, int actions, int state, int frame) {
        int next = game.f(keys, actions, state, frame);
        if (next != state && (next == 100 || next == 200)) {
            game.e();
            game.i();
        }
        return next;
    }
}
