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

import java.io.File;
import java.io.IOException;

import doja.tools.classfile.ClassFile;

/** 修正原版混淆類別中，僅靠回傳型別區分的音量方法參照。 */
public final class SoundModePatch {
    private SoundModePatch() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IOException("Usage: SoundModePatch <runtime-dir>");
        File file = new File(args[0], "SoundModeHack.class");
        ClassFile cls = ClassFile.read(file);
        patch(cls, "setMode", "a", "(I)[B", 2);
        patch(cls, "setMode", "b", "(I)I", 1);
        patch(cls, "setMode", "c", "(I)I", 1);
        patch(cls, "setBgmVolume", "b", "(I)I", 1);
        patch(cls, "setSfxVolume", "c", "(I)I", 1);
        cls.write(file);
    }

    private static void patch(ClassFile cls, String caller, String target,
            String compiledSignature, int expected) throws Exception {
        ClassFile.Member method = cls.findMethod(caller, "(Lb;I)V");
        int from = cls.findMethodRef("b", target, compiledSignature);
        if (method == null || method.code() == null || from == 0) {
            throw new IOException("Seiken sound runtime call changed: " + caller + " -> b." + target);
        }
        int to = cls.addMethodRef("b", target, "(I)V");
        ClassFile.Code code = method.code();
        int changed = 0;
        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            if (code.opcode(p) == 0xB6 && code.u2(p + 1) == from) {
                if (code.opcode(next) != 0x57) {
                    throw new IOException("Seiken sound runtime stack changed: " + caller);
                }
                code.putU2(p + 1, to);
                code.putByte(next, 0);
                changed++;
            }
            p = next;
        }
        if (changed != expected) {
            throw new IOException("Seiken sound runtime call count changed: " + caller
                    + " -> b." + target + " (" + changed + ")");
        }
    }
}
