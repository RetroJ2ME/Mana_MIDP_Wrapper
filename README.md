# 聖劍傳說 ～FF外傳～ Wrapper

聖劍傳說 ～FF外傳～, DoJa to MIDP Wrapper.

![](/img.webp)

## How to Use?

You only need [Apache Ant](https://ant.apache.org/bindownload.cgi)!

1. Put the DoJa 1.1.0 version JAR/SP/JAM into `games/`;
2. Run `ant`;
3. The MIDP version will be output to `dist/` :)


## Real Devices

JVM Heap ≥ 2048 KB, CPU  ≥ 234 Mhz

*Yes, it's that low...!*

*Nokia 5130XM Perfect!*


## Translations

Run the **ant** build once, and Translation.tsv will be generated under `comp/translation` for you to translate.

You can safely ignore Index.tsv, its only used internally to index the translations.


## LLM

Coding: 5.6 Sol Web，6 Sol Codex


## Test

Test video: [Blibili](https://www.bilibili.com/video/BV1GJhd6dE5F)

- KEmulator nnmod: Works
- FreeJ2ME-Plus: Works, use the libretro core.. smoother speed
- J2ME-Loader: Works
- Real device: Works


## Modules

- [MLD Player](https://github.com/Magstic/MLD_Player) — MLD to MID/WAV conversion.
- [DoJa to MIDP Core](https://github.com/RetroJ2ME/DoJa-to-MIDP-Core) — Core runtime and tools.
- [ProGuard 6.0.3](https://mvnrepository.com/artifact/com.guardsquare/proguard-base) — Game obfuscation and preverification.
- [CLDC API 1.1](https://mvnrepository.com/artifact/javax.microedition/cldc) — Basic CLDC API support.
- [MIDP API 2.0](https://mvnrepository.com/artifact/javax.microedition/midp) — Basic MIDP API support.

## Thanks

[J2ME Docs](https://nikita36078.github.io/J2ME_Docs/)

[Keitai Wiki](https://keitaiwiki.com/wiki/KeitaiWiki)

[Fusion Pixel Font](https://github.com/TakWolf/fusion-pixel-font)