package com.jojo.gh.arabic.launcher;

import org.json.JSONObject;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * محرك التعديل الموضعي لكتالوج الأصول الحي (In-Place Unity AssetBundleCatalog Patcher).
 *
 * يعمل بدون أي مكتبات أصلية (Pure Java + Tukaani XZ LZMA + Built-in LZ4):
 * 1) يقرأ حزم UnityFS (غير مضغوطة / LZMA / LZ4 / LZ4HC) ويحسب بصمة CRC32 المطابقة لمحرك Unity 100%.
 * 2) يستخرج قائمة الحزم المعرّبة وأحجامها وبصماتها إما من catalog_patch.json أو تلقائياً بمطابقة
 *    حزم UnityFS داخل مجلد التجهيز مع فهرس الحزمة.
 * 3) يفتح ملف assetbundlecatalog.asset الحي الموجود على جهاز اللاعب (حتى لو أضافت اللعبة
 *    مئات الفعاليات والبنرات الأسبوعية الجديدة عبر OTA)، ويحدّث فقط قيم (size, crc) للحزم
 *    المعرّبة موضعياً دون المساس ببقية إدخالات الكتالوج، ثم يعيد حفظه بصيغة UnityFS LZ4.
 */
public final class UnityCatalogPatcher {

    private static final int CHUNK_SIZE = 131072; // 128 KB UnityFS LZ4 chunks
    private static final byte[] CATALOG_ANCHOR = new byte[]{
            0x12, 0x00, 0x00, 0x00,
            'A', 's', 's', 'e', 't', 'B', 'u', 'n', 'd', 'l', 'e', 'C', 'a', 't', 'a', 'l', 'o', 'g',
            0x00, 0x00
    };

    private UnityCatalogPatcher() {
    }

    public static final class BundleNode {
        public final String name;
        public final int flags;
        public byte[] data;

        public BundleNode(String name, int flags, byte[] data) {
            this.name = name;
            this.flags = flags;
            this.data = data;
        }
    }

    public static final class UnityFsBundle {
        public String signature;
        public int version;
        public String versionPlayer;
        public String versionEngine;
        public int dataFlags;
        public final List<BundleNode> nodes = new ArrayList<>();
    }

    public static final class CatalogPatchResult {
        public final boolean patchedInPlace;
        public final int updatedEntries;
        public final int totalCatalogEntries;
        public final String detail;

        public CatalogPatchResult(boolean patchedInPlace, int updatedEntries, int totalCatalogEntries, String detail) {
            this.patchedInPlace = patchedInPlace;
            this.updatedEntries = updatedEntries;
            this.totalCatalogEntries = totalCatalogEntries;
            this.detail = detail != null ? detail : "";
        }
    }

    /**
     * يفحص بسرعة ما إذا كان الملف يبدأ بترويسة UnityFS القياسية.
     */
    public static boolean isUnityFsFile(File file) {
        if (file == null || !file.isFile() || file.length() < 32) {
            return false;
        }
        byte[] hdr = new byte[8];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(hdr) != 8) {
                return false;
            }
            return hdr[0] == 'U' && hdr[1] == 'n' && hdr[2] == 'i' && hdr[3] == 't'
                    && hdr[4] == 'y' && hdr[5] == 'F' && hdr[6] == 'S' && hdr[7] == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * يحسب بصمة Unity AssetBundle CRC32 الحقيقية على جميع عقد الملف المفكوكة (CAB + .resS)،
     * مطابقة 100% لبصمة assetbundlecatalog.asset.
     */
    public static long computeUnityFsCrc32(File bundleFile) throws IOException {
        UnityFsBundle bundle = readUnityFs(bundleFile);
        CRC32 crc = new CRC32();
        for (BundleNode node : bundle.nodes) {
            if (node.data != null && node.data.length > 0) {
                crc.update(node.data);
            }
        }
        return crc.getValue() & 0xFFFFFFFFL;
    }

    /**
     * يقرأ حزمة UnityFS كاملة ويفك ضغط جميع الكتل (Uncompressed / LZMA / LZ4 / LZ4HC).
     */
    public static UnityFsBundle readUnityFs(File file) throws IOException {
        byte[] raw = readAllBytes(file);
        return readUnityFsBytes(raw);
    }

    public static UnityFsBundle readUnityFsBytes(byte[] raw) throws IOException {
        if (raw == null || raw.length < 32) {
            throw new IOException("Invalid UnityFS file: too small");
        }
        int[] pos = new int[]{0};
        String sig = readCString(raw, pos);
        if (!"UnityFS".equals(sig)) {
            throw new IOException("Not a UnityFS bundle: " + sig);
        }

        UnityFsBundle bundle = new UnityFsBundle();
        bundle.signature = sig;
        bundle.version = readIntBE(raw, pos[0]);
        pos[0] += 4;
        bundle.versionPlayer = readCString(raw, pos);
        bundle.versionEngine = readCString(raw, pos);

        long bundleSize = readLongBE(raw, pos[0]);
        pos[0] += 8;
        int compBlockInfoSize = readIntBE(raw, pos[0]);
        pos[0] += 4;
        int uncompBlockInfoSize = readIntBE(raw, pos[0]);
        pos[0] += 4;
        int flags = readIntBE(raw, pos[0]);
        pos[0] += 4;
        bundle.dataFlags = flags;

        if (bundle.version >= 7) {
            pos[0] = (pos[0] + 15) & ~15;
        }

        int blockInfoOffset;
        int dataBlocksOffset;
        if ((flags & 0x80) != 0) {
            // BlockInfo at end of file
            int effectiveLen = (bundleSize > 0 && bundleSize <= raw.length) ? (int) bundleSize : raw.length;
            blockInfoOffset = effectiveLen - compBlockInfoSize;
            dataBlocksOffset = pos[0];
            if ((flags & 0x200) != 0) {
                dataBlocksOffset = (dataBlocksOffset + 15) & ~15;
            }
        } else {
            // BlockInfo immediately after header
            blockInfoOffset = pos[0];
            dataBlocksOffset = blockInfoOffset + compBlockInfoSize;
            if ((flags & 0x200) != 0) {
                dataBlocksOffset = (dataBlocksOffset + 15) & ~15;
            }
        }

        if (blockInfoOffset < 0 || blockInfoOffset + compBlockInfoSize > raw.length) {
            throw new IOException("UnityFS blockInfo out of bounds");
        }

        byte[] blockInfoBytes = decompressBlock(
                raw, blockInfoOffset, compBlockInfoSize, uncompBlockInfoSize, flags & 0x3F
        );

        // Parse blockInfo: 16-byte hash, blockCount, blocks[], nodeCount, nodes[]
        int biPos = 16;
        int blockCount = readIntBE(blockInfoBytes, biPos);
        biPos += 4;
        int[] blockUncomp = new int[blockCount];
        int[] blockComp = new int[blockCount];
        int[] blockFlags = new int[blockCount];
        long totalUncompLong = 0L;
        for (int i = 0; i < blockCount; i++) {
            blockUncomp[i] = readIntBE(blockInfoBytes, biPos);
            biPos += 4;
            blockComp[i] = readIntBE(blockInfoBytes, biPos);
            biPos += 4;
            blockFlags[i] = readShortBE(blockInfoBytes, biPos) & 0xFFFF;
            biPos += 2;
            totalUncompLong += (blockUncomp[i] & 0xFFFFFFFFL);
        }

        int nodeCount = readIntBE(blockInfoBytes, biPos);
        biPos += 4;
        long[] nodeOffset = new long[nodeCount];
        long[] nodeSize = new long[nodeCount];
        int[] nodeFlags = new int[nodeCount];
        String[] nodeName = new String[nodeCount];
        int[] biCursor = new int[]{biPos};
        for (int i = 0; i < nodeCount; i++) {
            nodeOffset[i] = readLongBE(blockInfoBytes, biCursor[0]);
            biCursor[0] += 8;
            nodeSize[i] = readLongBE(blockInfoBytes, biCursor[0]);
            biCursor[0] += 8;
            nodeFlags[i] = readIntBE(blockInfoBytes, biCursor[0]);
            biCursor[0] += 4;
            nodeName[i] = readCString(blockInfoBytes, biCursor);
        }

        if (totalUncompLong > Integer.MAX_VALUE - 16) {
            throw new IOException("UnityFS uncompressed payload too large: " + totalUncompLong);
        }
        byte[] allData = new byte[(int) totalUncompLong];
        int srcCur = dataBlocksOffset;
        int dstCur = 0;
        for (int i = 0; i < blockCount; i++) {
            int cType = blockFlags[i] & 0x3F;
            byte[] dec = decompressBlock(raw, srcCur, blockComp[i], blockUncomp[i], cType);
            System.arraycopy(dec, 0, allData, dstCur, dec.length);
            srcCur += blockComp[i];
            dstCur += dec.length;
        }

        for (int i = 0; i < nodeCount; i++) {
            int off = (int) nodeOffset[i];
            int sz = (int) nodeSize[i];
            byte[] slice = new byte[sz];
            System.arraycopy(allData, off, slice, 0, sz);
            bundle.nodes.add(new BundleNode(nodeName[i], nodeFlags[i], slice));
        }

        return bundle;
    }

    /**
     * يحفظ حزمة UnityFS بصيغة LZ4 chunk-based (128 KB chunks) متوافقة 100% مع Unity 6.
     */
    public static byte[] writeUnityFsLz4(UnityFsBundle bundle) throws IOException {
        ByteArrayOutputStream rawNodesOut = new ByteArrayOutputStream();
        long[] offsets = new long[bundle.nodes.size()];
        long[] sizes = new long[bundle.nodes.size()];
        long running = 0L;
        for (int i = 0; i < bundle.nodes.size(); i++) {
            BundleNode n = bundle.nodes.get(i);
            offsets[i] = running;
            sizes[i] = n.data.length;
            rawNodesOut.write(n.data);
            running += n.data.length;
        }
        byte[] allRaw = rawNodesOut.toByteArray();

        // Chunk-based LZ4 compression
        ByteArrayOutputStream compDataOut = new ByteArrayOutputStream(Math.max(4096, allRaw.length / 3));
        List<int[]> blockMeta = new ArrayList<>();
        int off = 0;
        while (off < allRaw.length) {
            int len = Math.min(CHUNK_SIZE, allRaw.length - off);
            byte[] comp = lz4Compress(allRaw, off, len);
            if (comp.length < len) {
                compDataOut.write(comp);
                blockMeta.add(new int[]{len, comp.length, 2}); // LZ4
            } else {
                compDataOut.write(allRaw, off, len);
                blockMeta.add(new int[]{len, len, 0}); // Uncompressed
            }
            off += len;
        }
        byte[] compFileData = compDataOut.toByteArray();

        // Build blockInfo
        ByteArrayOutputStream biRawOut = new ByteArrayOutputStream(256 + blockMeta.size() * 10);
        DataOutputStream biDos = new DataOutputStream(biRawOut);
        biDos.write(new byte[16]); // uncompressedDataHash
        biDos.writeInt(blockMeta.size());
        for (int[] bm : blockMeta) {
            biDos.writeInt(bm[0]);
            biDos.writeInt(bm[1]);
            biDos.writeShort(bm[2]);
        }
        biDos.writeInt(bundle.nodes.size());
        for (int i = 0; i < bundle.nodes.size(); i++) {
            BundleNode n = bundle.nodes.get(i);
            biDos.writeLong(offsets[i]);
            biDos.writeLong(sizes[i]);
            biDos.writeInt(n.flags);
            biDos.write(n.name.getBytes(StandardCharsets.UTF_8));
            biDos.writeByte(0);
        }
        biDos.flush();
        byte[] biUncomp = biRawOut.toByteArray();
        byte[] biComp = lz4Compress(biUncomp, 0, biUncomp.length);
        int dataFlag;
        byte[] biFinal;
        if (biComp.length < biUncomp.length) {
            dataFlag = 0xC2; // 194 = DirectoryInfo(0x40) | BlockInfoAtEnd(0x80) | LZ4(0x02)
            biFinal = biComp;
        } else {
            dataFlag = 0xC0; // 192 = DirectoryInfo(0x40) | BlockInfoAtEnd(0x80) | None(0x00)
            biFinal = biUncomp;
        }

        // Write final UnityFS
        ByteArrayOutputStream out = new ByteArrayOutputStream(compFileData.length + biFinal.length + 128);
        DataOutputStream dos = new DataOutputStream(out);
        dos.write(bundle.signature.getBytes(StandardCharsets.UTF_8));
        dos.writeByte(0);
        dos.writeInt(bundle.version);
        dos.write(bundle.versionPlayer.getBytes(StandardCharsets.UTF_8));
        dos.writeByte(0);
        dos.write(bundle.versionEngine.getBytes(StandardCharsets.UTF_8));
        dos.writeByte(0);

        int headerStartPos = out.size();
        dos.writeLong(0L); // placeholder bundle_size
        dos.writeInt(biFinal.length);
        dos.writeInt(biUncomp.length);
        dos.writeInt(dataFlag);
        dos.flush();

        if (bundle.version >= 7) {
            int rem = out.size() & 15;
            if (rem != 0) {
                out.write(new byte[16 - rem]);
            }
        }

        out.write(compFileData);
        out.write(biFinal);
        byte[] result = out.toByteArray();

        // Patch final bundle_size into header
        writeLongBE(result, headerStartPos, result.length);
        return result;
    }

    /**
     * يفحص ما إذا كانت عقدة CAB داخل الحزمة هي فهرس AssetBundleCatalog (مثل assetbundlecatalog.asset).
     */
    public static boolean isAssetBundleCatalog(File file) {
        if (!isUnityFsFile(file)) {
            return false;
        }
        try {
            UnityFsBundle b = readUnityFs(file);
            for (BundleNode n : b.nodes) {
                if (n.data != null && findCatalogHeader(n.data) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * يستخرج خريطة التحديثات المطلوبة (bundleName -> {size, crc32}) من:
     * 1) ملف catalog_patch.json داخل مجلد التجهيز إن وُجد، أو
     * 2) تلقائياً بمطابقة أحجام وبصمات CRC32 لجميع حزم UnityFS المعرّبة داخل مجلد التجهيز
     *    مقابل ملف الكتالوج المرفق داخل الحزمة (يعمل فوراً مع الحزم الحالية والمستقبلية!).
     */
    public static Map<String, long[]> extractOverrides(
            File stagedCatalogFile,
            List<File> stagedOtherUnityBundles,
            File optionalCatalogPatchJson
    ) throws IOException {
        Map<String, long[]> overrides = new HashMap<>();

        // 1) الأولوية القصوى لملف catalog_patch.json الصريح إن وُجد في الحزمة
        if (optionalCatalogPatchJson != null && optionalCatalogPatchJson.isFile()) {
            try {
                String txt = new String(readAllBytes(optionalCatalogPatchJson), StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(txt);
                JSONObject entries = root.optJSONObject("entries");
                if (entries != null) {
                    Iterator<String> keys = entries.keys();
                    while (keys.hasNext()) {
                        String bname = keys.next();
                        JSONObject item = entries.optJSONObject(bname);
                        if (item != null) {
                            long sz = item.optLong("size", -1L);
                            long crc = item.optLong("crc", -1L);
                            if (sz > 0 && crc >= 0) {
                                overrides.put(bname, new long[]{sz, crc});
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (!overrides.isEmpty()) {
            return overrides;
        }

        // 2) الاستنتاج التلقائي العام (100% Game-Agnostic) من حزم UnityFS المجهّزة + الكتالوج المجهّز
        if (stagedCatalogFile == null || !stagedCatalogFile.isFile() || stagedOtherUnityBundles == null || stagedOtherUnityBundles.isEmpty()) {
            return overrides;
        }

        Set<String> targetSigKeys = new HashSet<>();
        for (File bf : stagedOtherUnityBundles) {
            if (bf == null || !bf.isFile() || !isUnityFsFile(bf)) {
                continue;
            }
            try {
                long sz = bf.length();
                long crc = computeUnityFsCrc32(bf);
                targetSigKeys.add(sz + ":" + crc);
            } catch (Throwable ignored) {
            }
        }
        if (targetSigKeys.isEmpty()) {
            return overrides;
        }

        UnityFsBundle stagedCat = readUnityFs(stagedCatalogFile);
        for (BundleNode node : stagedCat.nodes) {
            if (node.data == null) {
                continue;
            }
            CatalogLayout layout = findCatalogHeader(node.data);
            if (layout == null) {
                continue;
            }
            byte[] cab = node.data;
            int cur = layout.infosStart;
            for (int i = 0; i < layout.count; i++) {
                int nAll = readIntLE(cab, cur);
                cur += 4 + nAll * 4;
                int nDir = readIntLE(cab, cur);
                cur += 4 + nDir * 4;
                int nHash = readIntLE(cab, cur);
                cur += 4 + nHash * 4;
                long crc = readIntLE(cab, cur) & 0xFFFFFFFFL;
                long sz = readIntLE(cab, cur + 4) & 0xFFFFFFFFL;
                cur += 8;

                if (targetSigKeys.contains(sz + ":" + crc)) {
                    overrides.put(layout.names.get(i), new long[]{sz, crc});
                }
            }
            break;
        }
        return overrides;
    }

    /**
     * يطبّق التعديل الموضعي (In-Place Patch) على ملف assetbundlecatalog.asset الحي الموجود على جهاز اللاعب:
     * يحدّث فقط قيم (crc, size) الخاصة بالحزم المعرّبة ويبقي جميع إدخالات الفعاليات والبنرات الجديدة سليمة 100%.
     */
    public static CatalogPatchResult patchLiveCatalogInPlace(
            File liveCatalogFile,
            File outputFile,
            Map<String, long[]> overrides
    ) throws IOException {
        if (liveCatalogFile == null || !liveCatalogFile.isFile()) {
            return new CatalogPatchResult(false, 0, 0, "الملف الحي للكتالوج غير موجود");
        }
        if (overrides == null || overrides.isEmpty()) {
            return new CatalogPatchResult(false, 0, 0, "لا توجد تحديثات كتالوج للتطبيق الموضعي");
        }

        UnityFsBundle liveBundle = readUnityFs(liveCatalogFile);
        int patchedCount = 0;
        int totalEntries = 0;

        for (BundleNode node : liveBundle.nodes) {
            if (node.data == null) {
                continue;
            }
            CatalogLayout layout = findCatalogHeader(node.data);
            if (layout == null) {
                continue;
            }
            totalEntries = layout.count;
            byte[] cab = node.data;
            int cur = layout.infosStart;
            for (int i = 0; i < layout.count; i++) {
                int nAll = readIntLE(cab, cur);
                cur += 4 + nAll * 4;
                int nDir = readIntLE(cab, cur);
                cur += 4 + nDir * 4;
                int nHash = readIntLE(cab, cur);
                cur += 4 + nHash * 4;

                String bname = layout.names.get(i);
                long[] target = overrides.get(bname);
                if (target != null) {
                    long newSize = target[0];
                    long newCrc = target[1];
                    writeIntLE(cab, cur, (int) (newCrc & 0xFFFFFFFFL));
                    writeIntLE(cab, cur + 4, (int) (newSize & 0xFFFFFFFFL));
                    patchedCount++;
                }
                cur += 8;
            }
            break;
        }

        if (patchedCount == 0) {
            return new CatalogPatchResult(false, 0, totalEntries, "لم تُطابق أي حزمة معرّبة داخل الكتالوج الحي");
        }

        byte[] packed = writeUnityFsLz4(liveBundle);
        writeAtomically(outputFile, packed);
        return new CatalogPatchResult(
                true,
                patchedCount,
                totalEntries,
                "تم التعديل الموضعي لـ " + patchedCount + " حزمة داخل الكتالوج الحي (" + totalEntries + " أصل)"
        );
    }

    /**
     * يقرأ رقم إصدار الكتالوج الداخلي (Catalog Version Integer، مثل 577044) من ملف الكتالوج،
     * أو يعيد -1 إذا لم يكن ملف كتالوج صالحاً.
     */
    public static int readCatalogVersion(File file) {
        if (!isUnityFsFile(file)) {
            return -1;
        }
        try {
            UnityFsBundle b = readUnityFs(file);
            for (BundleNode n : b.nodes) {
                if (n.data != null) {
                    CatalogLayout layout = findCatalogHeader(n.data);
                    if (layout != null) {
                        return layout.catalogVersion;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // =========================================================================
    // محلل بنية AssetBundleCatalog الديناميكي داخل CAB
    // =========================================================================

    private static final class CatalogLayout {
        final int catalogVersion;
        final int count;
        final List<String> names;
        final int infosStart;

        CatalogLayout(int catalogVersion, int count, List<String> names, int infosStart) {
            this.catalogVersion = catalogVersion;
            this.count = count;
            this.names = names;
            this.infosStart = infosStart;
        }
    }

    /**
     * يبحث عبر *جميع* مواضع سلسلة AssetBundleCatalog داخل CAB (لأن الموضع الأول والثاني يعودان
     * لكائن MonoScript واسم الـ Namespace، بينما الموضع الثالث يعود لكائن MonoBehaviour الفعلي الذي يحمل الجدول!).
     */
    static CatalogLayout findCatalogHeader(byte[] cab) {
        int from = 0;
        while (from < cab.length - CATALOG_ANCHOR.length) {
            int anchorPos = indexOfBytes(cab, CATALOG_ANCHOR, from);
            if (anchorPos < 0) {
                break;
            }
            from = anchorPos + 4;

            int searchStart = anchorPos + CATALOG_ANCHOR.length;
            int searchEnd = Math.min(cab.length - 8, searchStart + 64);
            int catVer = (searchStart + 4 <= cab.length) ? readIntLE(cab, searchStart) : 0;

            for (int cntPos = searchStart; cntPos <= searchEnd; cntPos += 4) {
                int cnt = readIntLE(cab, cntPos);
                if (cnt < 10 || cnt > 2_000_000) {
                    continue;
                }
                int cur = cntPos + 4;
                List<String> names = new ArrayList<>(Math.min(cnt, 1024));
                boolean ok = true;
                for (int i = 0; i < cnt; i++) {
                    if (cur + 4 > cab.length) {
                        ok = false;
                        break;
                    }
                    int slen = readIntLE(cab, cur);
                    // أسماء الحزم في AssetBundleCatalog تكون دائماً نصوص مسارات ASCII غير فارغة (slen >= 1)
                    if (slen <= 0 || slen > 512 || cur + 4 + slen > cab.length) {
                        ok = false;
                        break;
                    }
                    names.add(new String(cab, cur + 4, slen, StandardCharsets.UTF_8));
                    cur += 4 + slen;
                    int rem = cur & 3;
                    if (rem != 0) {
                        cur += 4 - rem;
                    }
                }
                if (ok && cur + 4 <= cab.length) {
                    int infosCnt = readIntLE(cab, cur);
                    if (infosCnt == cnt) {
                        return new CatalogLayout(catVer, cnt, names, cur + 4);
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // فك وضغط الكتل: LZMA (Tukaani XZ) + LZ4 (Pure Java)
    // =========================================================================

    private static byte[] decompressBlock(byte[] src, int offset, int compSize, int uncompSize, int compType) throws IOException {
        if (compType == 0) {
            byte[] out = new byte[uncompSize];
            System.arraycopy(src, offset, out, 0, Math.min(compSize, uncompSize));
            return out;
        } else if (compType == 1) {
            // UnityFS LZMA: 1 byte props + 4 bytes dictSize (LE) + raw LZMA stream
            if (compSize < 5) {
                throw new IOException("Invalid LZMA block size: " + compSize);
            }
            byte props = src[offset];
            int dictSize = readIntLE(src, offset + 1);
            ByteArrayInputStream bais = new ByteArrayInputStream(src, offset + 5, compSize - 5);
            try (LZMAInputStream lzma = new LZMAInputStream(bais, uncompSize & 0xFFFFFFFFL, props, dictSize);
                 DataInputStream dis = new DataInputStream(lzma)) {
                byte[] out = new byte[uncompSize];
                dis.readFully(out);
                return out;
            }
        } else if (compType == 2 || compType == 3) {
            return lz4Decompress(src, offset, compSize, uncompSize);
        } else {
            throw new IOException("Unsupported UnityFS compression type: " + compType);
        }
    }

    /**
     * فك ضغط LZ4 / LZ4HC القياسي (Block Format).
     */
    public static byte[] lz4Decompress(byte[] src, int srcOff, int srcLen, int dstLen) throws IOException {
        byte[] dst = new byte[dstLen];
        int s = srcOff;
        int sEnd = srcOff + srcLen;
        int d = 0;

        while (s < sEnd) {
            int token = src[s++] & 0xFF;
            int litLen = token >>> 4;
            if (litLen == 15) {
                while (s < sEnd) {
                    int b = src[s++] & 0xFF;
                    litLen += b;
                    if (b != 255) {
                        break;
                    }
                }
            }
            if (litLen > 0) {
                if (s + litLen > sEnd || d + litLen > dstLen) {
                    throw new IOException("LZ4 literal overflow");
                }
                System.arraycopy(src, s, dst, d, litLen);
                s += litLen;
                d += litLen;
            }
            if (s >= sEnd) {
                break;
            }
            int matchOffset = (src[s] & 0xFF) | ((src[s + 1] & 0xFF) << 8);
            s += 2;
            if (matchOffset == 0 || matchOffset > d) {
                throw new IOException("LZ4 invalid match offset: " + matchOffset);
            }
            int matchLen = token & 0x0F;
            if (matchLen == 15) {
                while (s < sEnd) {
                    int b = src[s++] & 0xFF;
                    matchLen += b;
                    if (b != 255) {
                        break;
                    }
                }
            }
            matchLen += 4;
            if (d + matchLen > dstLen) {
                throw new IOException("LZ4 match overflow");
            }
            int matchPos = d - matchOffset;
            for (int i = 0; i < matchLen; i++) {
                dst[d++] = dst[matchPos + i];
            }
        }
        return dst;
    }

    /**
     * ضغط LZ4 قياسي سريع (Hash-Table Block Compressor) متوافق 100% مع UnityFS.
     */
    public static byte[] lz4Compress(byte[] src, int srcOff, int srcLen) {
        if (srcLen <= 0) {
            return new byte[0];
        }
        int maxDst = srcLen + (srcLen / 255) + 16;
        byte[] dst = new byte[maxDst];
        int[] hashTable = new int[4096];
        Arrays.fill(hashTable, -1);

        int s = srcOff;
        int anchor = srcOff;
        int sEnd = srcOff + srcLen;
        int mflimit = sEnd - 12;
        int matchLimit = sEnd - 5;
        int d = 0;

        if (srcLen >= 13) {
            hashTable[lz4Hash(src, s)] = s;
            s++;
            while (s <= mflimit) {
                int h = lz4Hash(src, s);
                int ref = hashTable[h];
                hashTable[h] = s;

                if (ref < srcOff || (s - ref) > 65535 || !equals4(src, ref, s)) {
                    s++;
                    continue;
                }

                // Found 4+ byte match
                int litLen = s - anchor;
                int matchLen = 4;
                while (s + matchLen < matchLimit && src[ref + matchLen] == src[s + matchLen]) {
                    matchLen++;
                }

                int tokenPos = d++;
                int litToken = Math.min(litLen, 15);
                int matchToken = Math.min(matchLen - 4, 15);
                dst[tokenPos] = (byte) ((litToken << 4) | matchToken);

                if (litLen >= 15) {
                    int rem = litLen - 15;
                    while (rem >= 255) {
                        dst[d++] = (byte) 255;
                        rem -= 255;
                    }
                    dst[d++] = (byte) rem;
                }
                if (litLen > 0) {
                    System.arraycopy(src, anchor, dst, d, litLen);
                    d += litLen;
                }

                int offset = s - ref;
                dst[d++] = (byte) (offset & 0xFF);
                dst[d++] = (byte) ((offset >>> 8) & 0xFF);

                if (matchLen - 4 >= 15) {
                    int rem = (matchLen - 4) - 15;
                    while (rem >= 255) {
                        dst[d++] = (byte) 255;
                        rem -= 255;
                    }
                    dst[d++] = (byte) rem;
                }

                s += matchLen;
                anchor = s;
                if (s <= mflimit) {
                    hashTable[lz4Hash(src, s - 2)] = s - 2;
                }
            }
        }

        // Write final literals
        int lastLit = sEnd - anchor;
        int litToken = Math.min(lastLit, 15);
        dst[d++] = (byte) (litToken << 4);
        if (lastLit >= 15) {
            int rem = lastLit - 15;
            while (rem >= 255) {
                dst[d++] = (byte) 255;
                rem -= 255;
            }
            dst[d++] = (byte) rem;
        }
        if (lastLit > 0) {
            System.arraycopy(src, anchor, dst, d, lastLit);
            d += lastLit;
        }
        return Arrays.copyOf(dst, d);
    }

    private static int lz4Hash(byte[] buf, int pos) {
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        return ((v * -1640531535) >>> 20) & 0xFFF;
    }

    private static boolean equals4(byte[] buf, int a, int b) {
        return buf[a] == buf[b]
                && buf[a + 1] == buf[b + 1]
                && buf[a + 2] == buf[b + 2]
                && buf[a + 3] == buf[b + 3];
    }

    // =========================================================================
    // أدوات قراءة/كتابة بايتات مساعدة
    // =========================================================================

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(file.length(), 1024 * 1024))) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }

    private static void writeAtomically(File dst, byte[] data) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, dst.getName() + ".cat_tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.flush();
        }
        if (dst.exists()) {
            dst.delete();
        }
        if (!tmp.renameTo(dst)) {
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                fos.write(data);
                fos.flush();
            }
            tmp.delete();
        }
    }

    private static String readCString(byte[] data, int[] pos) {
        int start = pos[0];
        int cur = start;
        while (cur < data.length && data[cur] != 0) {
            cur++;
        }
        pos[0] = Math.min(data.length, cur + 1);
        return new String(data, start, cur - start, StandardCharsets.UTF_8);
    }

    private static int readShortBE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int readIntBE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static long readLongBE(byte[] b, int o) {
        return ((readIntBE(b, o) & 0xFFFFFFFFL) << 32) | (readIntBE(b, o + 4) & 0xFFFFFFFFL);
    }

    private static void writeLongBE(byte[] b, int o, long v) {
        b[o] = (byte) ((v >>> 56) & 0xFF);
        b[o + 1] = (byte) ((v >>> 48) & 0xFF);
        b[o + 2] = (byte) ((v >>> 40) & 0xFF);
        b[o + 3] = (byte) ((v >>> 32) & 0xFF);
        b[o + 4] = (byte) ((v >>> 24) & 0xFF);
        b[o + 5] = (byte) ((v >>> 16) & 0xFF);
        b[o + 6] = (byte) ((v >>> 8) & 0xFF);
        b[o + 7] = (byte) (v & 0xFF);
    }

    private static int readIntLE(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void writeIntLE(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
        b[o + 2] = (byte) ((v >>> 16) & 0xFF);
        b[o + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle, int from) {
        int limit = haystack.length - needle.length;
        outer:
        for (int i = from; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
