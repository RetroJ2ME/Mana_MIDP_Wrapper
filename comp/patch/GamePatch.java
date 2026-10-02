import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import doja.tools.classfile.ClassFile;

/**
 * HACK：修補 Softkey 鎖定機制。
 *
 * 原版遊戲會在 processEvent() 中直接修改 c.K，但許多 UI 處理邏輯在檢查 c.K 之前會先將其清空。
 * 因此在 MIDP 實機硬體上，按鍵輸入是否有效，完全取決於非同步回呼 (callback) 觸發的時機點（存在競爭條件）。
 * 
 * 這裡透過 KeyHack 將 int K 欄位虛擬化來解決此問題：
 *
 *  - processEvent 改為將動作 30/31 放入佇列。
 *  - frame/UI 的「清除」操作（讀取-修改-寫入）改用 peekK()，確保不會消耗掉尚未被讀取的待處理按鍵。
 *  - 實際的 K 值檢查改用 readK()，這會以原子操作具現化待處理的按鍵。
 *  - 所有針對 K 的寫入操作都會強制透過 setK() 進行。
 *
 */
public final class GamePatch {
    private static final int GETFIELD = 0xB4;
    private static final int PUTFIELD = 0xB5;
    private static final int INVOKEVIRTUAL = 0xB6;
    private static final int INVOKESPECIAL = 0xB7;
    private static final int INVOKESTATIC = 0xB8;
    private static final int BIPUSH = 0x10;
    private static final int IAND = 0x7E;

    private GamePatch() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IOException("Usage: GamePatch <class-dir>");
        File classes = new File(args[0]);
        File file = new File(classes, "c.class");
        ClassFile cls = ClassFile.read(file);

        // enqueue() keeps action 32's original direct behavior by invoking this
        // method from the helper; expose only this game-private dispatcher.
        require("c.b(I)V", cls.exposeMethod("b", "(I)V"), 1);

        int originalDispatch = cls.findMethodRef("c", "b", "(I)V");
        int kField = cls.findFieldRef("c", "K", "I");
        if (originalDispatch == 0 || kField == 0) {
            throw new IOException("Seiken soft-key symbols not found");
        }

        int enqueue = cls.addMethodRef("KeyHack", "enqueue", "(Lc;I)V");
        int readK = cls.addMethodRef("KeyHack", "readK", "(Lc;)I");
        int peekK = cls.addMethodRef("KeyHack", "peekK", "(Lc;)I");
        int setK = cls.addMethodRef("KeyHack", "setK", "(Lc;I)V");

        // processEvent has exactly two calls to c.b(I)V: SOFT1 and SOFT2.
        ClassFile.Member process = cls.findMethod("processEvent", "(II)V");
        if (process == null || process.code() == null) throw new IOException("c.processEvent(II)V not found");
        int softRedirects = redirectCalls(process.code(), originalDispatch, enqueue);
        require("processEvent soft-key dispatches", softRedirects, 2);

        int gets = 0;
        int clearGets = 0;
        int puts = 0;
        List<ClassFile.Member> methods = cls.methods();
        for (int mi = 0; mi < methods.size(); mi++) {
            ClassFile.Code code = methods.get(mi).code();
            if (code == null) continue;

            // First identify the GETFIELD K instructions that are part of the
            // exact "K &= negativeMask" clear idiom.  Those must NOT surface a
            // pending key before the clear executes.
            Set<Integer> clearReadOffsets = new HashSet<Integer>();
            for (int p = 0; p < code.length();) {
                int next = code.next(p);
                if (code.opcode(p) == GETFIELD && code.u2(p + 1) == kField) {
                    gets++;
                    if (isMaskedClear(code, p, kField)) {
                        clearReadOffsets.add(Integer.valueOf(p));
                        clearGets++;
                    }
                } else if (code.opcode(p) == PUTFIELD && code.u2(p + 1) == kField) {
                    puts++;
                }
                p = next;
            }

            // GETFIELD c.K:I and PUTFIELD c.K:I have the same 3-byte width and
            // stack effect as these static helper calls.
            for (int p = 0; p < code.length();) {
                int next = code.next(p);
                int op = code.opcode(p);
                if (op == GETFIELD && code.u2(p + 1) == kField) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, clearReadOffsets.contains(Integer.valueOf(p)) ? peekK : readK);
                } else if (op == PUTFIELD && code.u2(p + 1) == kField) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, setK);
                }
                p = next;
            }
        }

        require("c.K GETFIELD count", gets, 78);
        require("c.K masked-clear GETFIELD count", clearGets, 43);
        require("c.K PUTFIELD count", puts, 50);

        patchOptionsPersistence(cls);
        patchSoundOptions(cls);
        patchNameSaveCodec(cls);
        patchSaveSlotName(cls);
        patchWindowColor(cls);
        patchWholeSpriteDraw(cls);
        cls.write(file);
        System.out.println("GamePatch: Seiken soft-key K latch virtualized (78 reads, 43 clear-reads, 50 writes)");

        patchTitleText(new File(classes, "b.class"));
        patchSpriteLoader(new File(classes, "a.class"));
    }

    private static void patchWholeSpriteDraw(ClassFile cls) throws Exception {
        ClassFile.Member paint = cls.findMethod("a", "(Lcom/nttdocomo/ui/Graphics;I)V");
        int original = cls.findMethodRef("com/nttdocomo/ui/Graphics", "drawImage",
                "(Lcom/nttdocomo/ui/Image;II)V");
        if (paint == null || paint.code() == null || original == 0) {
            throw new IOException("Seiken whole-sprite draw not found");
        }
        int replacement = cls.addMethodRef("SpriteAtlasHack", "drawWhole",
                "(Lcom/nttdocomo/ui/Graphics;Lcom/nttdocomo/ui/Image;II)V");
        require("whole-sprite draws", redirectVirtualCalls(paint.code(), original, replacement), 2);
    }

    private static void patchSpriteLoader(File file) throws Exception {
        ClassFile cls = ClassFile.read(file);
        int load = cls.findMethodRef("com/nttdocomo/ui/MediaManager", "getImage",
                "(Ljava/lang/String;)Lcom/nttdocomo/ui/MediaImage;");
        int image = cls.findInterfaceMethodRef("com/nttdocomo/ui/MediaImage", "getImage",
                "()Lcom/nttdocomo/ui/Image;");
        int dispose = cls.findMethodRef("com/nttdocomo/ui/Image", "dispose", "()V");
        int width = cls.findMethodRef("com/nttdocomo/ui/Image", "getWidth", "()I");
        int height = cls.findMethodRef("com/nttdocomo/ui/Image", "getHeight", "()I");
        if (load == 0 || image == 0 || dispose == 0 || width == 0 || height == 0) {
            throw new IOException("Seiken image loader changed");
        }
        int mappedLoad = cls.addMethodRef("SpriteAtlasHack", "loadImage",
                "(Ljava/lang/String;)Lcom/nttdocomo/ui/MediaImage;");
        int mappedImage = cls.addMethodRef("SpriteAtlasHack", "getImage",
                "(Lcom/nttdocomo/ui/MediaImage;)Lcom/nttdocomo/ui/Image;");
        int mappedDispose = cls.addMethodRef("SpriteAtlasHack", "dispose",
                "(Lcom/nttdocomo/ui/Image;)V");
        int mappedWidth = cls.addMethodRef("SpriteAtlasHack", "width",
                "(Lcom/nttdocomo/ui/Image;)I");
        int mappedHeight = cls.addMethodRef("SpriteAtlasHack", "height",
                "(Lcom/nttdocomo/ui/Image;)I");
        int loads = 0, images = 0, disposals = 0, widths = 0, heights = 0;
        String[] loaderNames = { "a", "b" };
        for (int i = 0; i < loaderNames.length; i++) {
            ClassFile.Member method = cls.findMethod(loaderNames[i], "(ILjava/lang/String;)V");
            if (method == null || method.code() == null) throw new IOException("Seiken image loader missing");
            ClassFile.Code code = method.code();
            for (int p = 0; p < code.length();) {
                int next = code.next(p);
                int op = code.opcode(p);
                int ref = (op == INVOKESTATIC || op == INVOKEVIRTUAL || op == 0xB9) ? code.u2(p + 1) : 0;
                if (op == INVOKESTATIC && ref == load) {
                    code.putU2(p + 1, mappedLoad);
                    loads++;
                } else if (op == 0xB9 && ref == image) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, mappedImage);
                    code.putByte(p + 3, 0);
                    code.putByte(p + 4, 0);
                    images++;
                } else if (op == INVOKEVIRTUAL && ref == dispose) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, mappedDispose);
                    disposals++;
                } else if (op == INVOKEVIRTUAL && ref == width) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, mappedWidth);
                    widths++;
                } else if (op == INVOKEVIRTUAL && ref == height) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, mappedHeight);
                    heights++;
                }
                p = next;
            }
        }
        require("sprite image loads", loads, 2);
        require("sprite image objects", images, 2);
        require("sprite image disposals", disposals, 2);
        require("sprite original widths", widths, 2);
        require("sprite original heights", heights, 2);

        ClassFile.Member draw = cls.findMethod("a", "(Lcom/nttdocomo/ui/Graphics;"
                + "Lcom/nttdocomo/ui/Image;IIIIII)V");
        int flip = cls.findMethodRef("com/nttdocomo/ui/Graphics", "setFlipMode", "(I)V");
        int crop = cls.findMethodRef("com/nttdocomo/ui/Graphics", "drawImage",
                "(Lcom/nttdocomo/ui/Image;IIIIII)V");
        if (draw == null || draw.code() == null || flip == 0 || crop == 0) {
            throw new IOException("Seiken sprite crop method changed");
        }
        int mappedFlip = cls.addMethodRef("SpriteAtlasHack", "setFlipMode",
                "(Lcom/nttdocomo/opt/ui/Graphics2;I)V");
        int mappedCrop = cls.addMethodRef("SpriteAtlasHack", "draw",
                "(Lcom/nttdocomo/opt/ui/Graphics2;Lcom/nttdocomo/ui/Image;IIIIII)V");
        require("sprite flip calls", redirectVirtualCalls(draw.code(), flip, mappedFlip), 2);
        require("sprite crop calls", redirectVirtualCalls(draw.code(), crop, mappedCrop), 1);
        cls.write(file);
    }

    private static int redirectVirtualCalls(ClassFile.Code code, int original, int replacement) throws Exception {
        int changed = 0;
        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            if (code.opcode(p) == INVOKEVIRTUAL && code.u2(p + 1) == original) {
                code.putByte(p, INVOKESTATIC);
                code.putU2(p + 1, replacement);
                changed++;
            }
            p = next;
        }
        return changed;
    }

    private static void patchTitleText(File file) throws Exception {
        ClassFile cls = ClassFile.read(file);
        String graphics = "Lcom/nttdocomo/ui/Graphics;";
        ClassFile.Member title = cls.findMethod("a", "(" + graphics
                + "Lcom/nttdocomo/ui/Font;ILa;ILcom/nttdocomo/ui/Image;III"
                + "[Ljava/lang/String;III)Z");
        if (title == null || title.code() == null) throw new IOException("Seiken title menu method not found");
        ClassFile.Code code = title.code();

        int originalColor = cls.findMethodRef("b", "a", "(" + graphics + "III)V");
        int originalText = cls.findMethodRef("b", "a", "(" + graphics + "Ljava/lang/String;II)V");
        if (originalColor == 0 || originalText == 0 || code.opcode(329) != 0x12
                || !cls.string(code.u1(330)).startsWith("スタート")) {
            throw new IOException("Seiken title menu bytecode changed");
        }

        int titleColor = cls.addMethodRef("TitleTextHack", "setTitleColor",
                "(" + graphics + "III)V");
        int titleText = cls.addMethodRef("TitleTextHack", "drawTitleText",
                "(" + graphics + "Ljava/lang/String;II)V");
        int[] colorCalls = {245, 264};
        int[] textCalls = {326, 392, 427, 475, 503, 531};
        for (int i = 0; i < colorCalls.length; i++) {
            replaceTitleCall(code, colorCalls[i], originalColor, titleColor);
        }
        for (int i = 0; i < textCalls.length; i++) {
            replaceTitleCall(code, textCalls[i], originalText, titleText);
        }
        patchTextOffset(cls);
        patchOpeningFont(cls);
        patchSoundModeLabel(cls);
        patchNameInput(cls);
        cls.write(file);
        System.out.println("GamePatch: Seiken text raised 1px; title text keeps soft three-sided shadow; opening credits use 24px font");
    }

    private static void patchOpeningFont(ClassFile cls) throws Exception {
        ClassFile.Member opening = cls.findMethod("a", "(Lcom/nttdocomo/ui/Graphics;"
                + "Lcom/nttdocomo/ui/Font;ILa;IJILcom/nttdocomo/ui/Image;)V");
        int originalFont = cls.findMethodRef("com/nttdocomo/ui/Font", "getFont",
                "(I)Lcom/nttdocomo/ui/Font;");
        if (opening == null || opening.code() == null || originalFont == 0) {
            throw new IOException("Seiken opening credits method not found");
        }
        ClassFile.Code code = opening.code();
        if (code.opcode(143) != 0x12 || code.u1(144) != 210
                || code.opcode(145) != INVOKESTATIC || code.u2(146) != originalFont) {
            throw new IOException("Seiken opening credits font call changed");
        }
        int replacement = cls.addMethodRef("TitleTextHack", "openingFont",
                "(I)Lcom/nttdocomo/ui/Font;");
        code.putU2(146, replacement);
    }

    private static void patchTextOffset(ClassFile cls) throws Exception {
        String graphics = "Lcom/nttdocomo/ui/Graphics;";
        ClassFile.Member draw = cls.findMethod("a", "(" + graphics + "Ljava/lang/String;II)V");
        int drawString = cls.findMethodRef("com/nttdocomo/ui/Graphics", "drawString", "(Ljava/lang/String;II)V");
        if (draw == null || draw.code() == null || drawString == 0) {
            throw new IOException("Seiken text drawing method not found");
        }
        ClassFile.Code code = draw.code();
        if (code.length() != 10 || code.opcode(4) != 0x03 || code.opcode(5) != 0x60
                || code.opcode(6) != INVOKEVIRTUAL || code.u2(7) != drawString) {
            throw new IOException("Seiken text drawing bytecode changed");
        }
        code.putByte(4, 0x02);
    }

    private static void patchWindowColor(ClassFile cls) throws Exception {
        int original = cls.findMethodRef("a", "a", "(ILjava/lang/String;)V");
        if (original == 0) throw new IOException("Seiken window image loader not found");
        int replacement = cls.addMethodRef("WindowHack", "load", "(La;ILjava/lang/String;)V");
        redirectWindowLoad(cls.findMethod("k", "()V"), original, replacement, 2, 2);
        redirectWindowLoad(cls.findMethod("f", "(IIII)I"), original, replacement, 1, 1);
    }

    private static void patchSoundOptions(ClassFile cls) throws Exception {
        ClassFile.Member options = cls.findMethod("f", "(IIII)I");
        if (options == null || options.code() == null) {
            throw new IOException("Seiken sound option method not found");
        }
        ClassFile.Code code = options.code();
        if (code.opcode(168) != 0x05 || code.opcode(169) != 0x60
                || code.opcode(170) != 0x05 || code.opcode(171) != 0x70) {
            throw new IOException("Seiken sound option cycle changed");
        }
        // 原版 0=OFF、1=ON；擴展成 0=OFF、1=ALL、2=BGM、3=SFX。
        code.putByte(168, 0x07);
        code.putByte(170, 0x07);

        redirectSoundCall(cls, "c", "(I)V", "a", "setMode");
        redirectSoundCall(cls, "d", "(I)V", "b", "setBgmVolume");
        redirectSoundCall(cls, "e", "(I)V", "c", "setSfxVolume");
        redirectSoundCall(cls, "a", "(II)V", "d", "play");
    }

    private static void redirectSoundCall(ClassFile cls, String caller, String signature,
            String original, String replacement) throws Exception {
        ClassFile.Member method = cls.findMethod(caller, signature);
        int from = cls.findMethodRef("b", original, "(I)V");
        if (method == null || method.code() == null || from == 0) {
            throw new IOException("Seiken sound method changed: c." + caller + signature);
        }
        int to = cls.addMethodRef("SoundModeHack", replacement, "(Lb;I)V");
        require("Seiken sound call " + caller + signature,
                redirectVirtualCalls(method.code(), from, to), 1);
    }

    private static void patchSoundModeLabel(ClassFile cls) throws Exception {
        ClassFile.Member options = cls.findMethod("a", "(Lcom/nttdocomo/ui/Graphics;"
                + "Lcom/nttdocomo/ui/Font;La;Lcom/nttdocomo/ui/Image;IIIIIIIII)V");
        int strings = cls.findFieldRef("b", "b", "[Ljava/lang/String;");
        if (options == null || options.code() == null || strings == 0) {
            throw new IOException("Seiken sound option drawing method not found");
        }
        ClassFile.Code code = options.code();
        if (code.opcode(228) != 0x2A || code.opcode(229) != GETFIELD
                || code.u2(230) != strings || code.opcode(232) != 0x11
                || code.u2(233) != 183 || code.opcode(235) != 0x15
                || code.u1(236) != 14 || code.opcode(237) != 0x60
                || code.opcode(238) != 0x32) {
            throw new IOException("Seiken sound option text lookup changed");
        }
        int label = cls.addMethodRef("SoundModeHack", "label", "(I)Ljava/lang/String;");
        code.putByte(228, 0x15);
        code.putByte(229, 14);
        code.putByte(230, INVOKESTATIC);
        code.putU2(231, label);
        for (int p = 233; p <= 238; p++) code.putByte(p, 0);
    }

    private static void patchNameInput(ClassFile cls) throws Exception {
        ClassFile.Member input = cls.findMethod("a", "(Ljava/lang/String;IILc;)Ljava/lang/String;");
        if (input == null || input.code() == null) {
            throw new IOException("Seiken name input method not found");
        }
        ClassFile.Code code = input.code();
        if (code.opcode(68) != 0x03 || code.opcode(69) != 0x36 || code.u1(70) != 8
                || code.opcode(159) != 0x15 || code.u1(160) != 8
                || code.opcode(161) != 0x04 || code.opcode(162) != 0x9F
                || code.opcode(170) != 0x2B || code.opcode(173) != 0x19
                || code.u1(174) != 5 || code.opcode(175) != 0xB0) {
            throw new IOException("Seiken name input validation changed");
        }

        // 略過 namesz.txt 的日文白名單，讓位圖字型處理缺字顯示。
        code.putByte(68, 0xA7);
        code.putU2(69, 159 - 68);
        int accept = cls.addMethodRef("NameInputHack", "accept",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        code.putByte(159, 0x19);
        code.putByte(160, 5);
        code.putByte(161, 0x2B);
        code.putByte(162, INVOKESTATIC);
        code.putU2(163, accept);
        code.putByte(165, 0xB0);
        for (int p = 166; p <= 175; p++) code.putByte(p, 0);
    }

    private static void patchNameSaveCodec(ClassFile cls) throws Exception {
        ClassFile.Member save = cls.findMethod("g", "()V");
        ClassFile.Member load = cls.findMethod("h", "()V");
        int encode = cls.findMethodRef("c", "a", "(Ljava/lang/String;)[I");
        int decode = cls.findMethodRef("c", "a", "([I)Ljava/lang/String;");
        if (save == null || save.code() == null || load == null || load.code() == null
                || encode == 0 || decode == 0) {
            throw new IOException("Seiken name save codec changed");
        }
        int newEncode = cls.addMethodRef("NameInputHack", "encode", "(Lc;Ljava/lang/String;)[I");
        int newDecode = cls.addMethodRef("NameInputHack", "decode", "(Lc;[I)Ljava/lang/String;");
        require("name save encoding", redirectCalls(save.code(), encode, newEncode), 1);
        require("name save decoding", redirectCalls(load.code(), decode, newDecode), 1);
    }

    // 存檔欄位會自行讀取並顯示姓名，不會經過一般讀檔的姓名解碼。
    // 原版只認得姓名字表中的編號；改名後若含有字表外的字元，就會把我們儲存的 Unicode 編碼誤當成字表位置，造成越界、畫面卡住。
    // 這裡讓存檔欄位也使用 NameInputHack，與一般讀檔採用相同的解碼規則。
    private static void patchSaveSlotName(ClassFile cls) throws Exception {
        ClassFile.Member display = cls.findMethod("a", "(Lcom/nttdocomo/ui/Graphics;II)V");
        int gameText = cls.findFieldRef("c", "a", "Lb;");
        int characters = cls.findFieldRef("b", "a", "Ljava/lang/String;");
        int substring = cls.findMethodRef("java/lang/String", "substring", "(II)Ljava/lang/String;");
        int concat = cls.findMethodRef("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
        if (display == null || display.code() == null || gameText == 0 || characters == 0
                || substring == 0 || concat == 0) {
            throw new IOException("Seiken save-slot name display not found");
        }
        ClassFile.Code code = display.code();
        // 先確認這段仍是原版的姓名查表指令，避免遊戲版本不同時改錯位置。
        if (code.length() < 309
                || code.opcode(288) != 0x19 || code.u1(289) != 17
                || code.opcode(290) != 0x2A
                || code.opcode(291) != GETFIELD || code.u2(292) != gameText
                || code.opcode(294) != GETFIELD || code.u2(295) != characters
                || code.opcode(297) != 0x15 || code.u1(298) != 19
                || code.opcode(299) != 0x15 || code.u1(300) != 19
                || code.opcode(301) != 0x04 || code.opcode(302) != 0x60
                || code.opcode(303) != INVOKEVIRTUAL || code.u2(304) != substring
                || code.opcode(306) != INVOKEVIRTUAL || code.u2(307) != concat) {
            throw new IOException("Seiken save-slot name decoding changed");
        }

        // 把「從原版字表取字」改成「交給 NameInputHack 解碼」。
        int decode = cls.addMethodRef("NameInputHack", "decodeCharacter", "(I)Ljava/lang/String;");
        code.putByte(290, 0x15);
        code.putByte(291, 19);
        code.putByte(292, INVOKESTATIC);
        code.putU2(293, decode);
        // 剩下的位置填入  NOP，讓後面的指令與跳轉位置保持不變。
        for (int p = 295; p <= 305; p++) code.putByte(p, 0);
    }

    private static void patchOptionsPersistence(ClassFile cls) throws Exception {
        if (cls.findString("scratchpad:///0;pos=0") == 0
                || cls.findString("scratchpad:///0;pos=0,length=20480") == 0
                || cls.findString("scratchpad:///1;pos=0,length=2") == 0) {
            throw new IOException("Seiken Scratchpad access changed");
        }
        require("option update method", cls.exposeMethod("f", "(IIII)I"), 1);
        require("option settings serializer", cls.exposeMethod("e", "()V"), 1);
        require("option Scratchpad writer", cls.exposeMethod("i", "()I"), 1);

        int original = cls.findMethodRef("c", "f", "(IIII)I");
        if (original == 0) throw new IOException("Seiken option update call not found");
        int replacement = cls.addMethodRef("OptionsHack", "update", "(Lc;IIII)I");
        int changed = 0;
        List<ClassFile.Member> methods = cls.methods();
        for (int i = 0; i < methods.size(); i++) {
            ClassFile.Code code = methods.get(i).code();
            if (code != null) changed += redirectCalls(code, original, replacement);
        }
        require("option update calls", changed, 1);
    }

    private static void redirectWindowLoad(ClassFile.Member method, int original, int replacement,
            int expectedCalls, int windowCall) throws Exception {
        if (method == null || method.code() == null) throw new IOException("Seiken window color method not found");
        ClassFile.Code code = method.code();
        int calls = 0;
        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            if (code.opcode(p) == INVOKEVIRTUAL && code.u2(p + 1) == original) {
                calls++;
                if (calls == windowCall) {
                    code.putByte(p, INVOKESTATIC);
                    code.putU2(p + 1, replacement);
                }
            }
            p = next;
        }
        require("Seiken window image calls", calls, expectedCalls);
    }

    private static void replaceTitleCall(ClassFile.Code code, int offset, int originalRef, int newRef) {
        if (code.opcode(offset) != INVOKESTATIC || code.u2(offset + 1) != originalRef) {
            throw new IllegalStateException("Seiken title menu call changed at " + offset);
        }
        code.putU2(offset + 1, newRef);
    }

    private static int redirectCalls(ClassFile.Code code, int fromRef, int toRef) throws Exception {
        int changed = 0;
        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            if (code.opcode(p) == INVOKESPECIAL && code.u2(p + 1) == fromRef) {
                code.putByte(p, INVOKESTATIC);
                code.putU2(p + 1, toRef);
                changed++;
            }
            p = next;
        }
        return changed;
    }

    private static boolean isMaskedClear(ClassFile.Code code, int getOffset, int kField) throws Exception {
        int mask = code.next(getOffset);
        if (mask >= code.length() || code.opcode(mask) != BIPUSH) return false;
        int value = (byte)code.u1(mask + 1);
        if (value != -2 && value != -3 && value != -4) return false;

        int and = code.next(mask);
        if (and >= code.length() || code.opcode(and) != IAND) return false;
        int put = code.next(and);
        return put < code.length() && code.opcode(put) == PUTFIELD && code.u2(put + 1) == kField;
    }

    private static void require(String label, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(label + ": expected " + expected + ", got " + actual);
        }
    }
}
